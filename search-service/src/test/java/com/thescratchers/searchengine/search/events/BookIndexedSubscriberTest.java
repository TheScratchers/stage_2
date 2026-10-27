package com.thescratchers.searchengine.search.events;

import com.thescratchers.searchengine.common.Sqlite;
import com.thescratchers.searchengine.search.cache.FilterValuesCache;
import com.thescratchers.searchengine.search.store.DatamartReader;
import com.thescratchers.searchengine.search.store.TestDatamart;
import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the subscriber against a real, non-persistent ActiveMQ broker on a free local port. */
class BookIndexedSubscriberTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path tempDir;

    private Path datamart;
    private FilterValuesCache cache;
    private BrokerService broker;
    private BookIndexedSubscriber subscriber;

    @BeforeEach
    void setUp() throws SQLException {
        datamart = TestDatamart.create(tempDir.resolve("datamart.db"));
        cache = new FilterValuesCache(new DatamartReader(datamart));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (subscriber != null) {
            subscriber.close();
        }
        stopBroker();
    }

    @Test
    void wrapsTheBrokerUrlInTheFailoverTransport() {
        assertEquals("failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=10000",
                BookIndexedSubscriber.failoverUrl("tcp://localhost:61616"));
        assertEquals("failover:(tcp://a:1,tcp://b:2)", BookIndexedSubscriber.failoverUrl("failover:(tcp://a:1,tcp://b:2)"));
    }

    @Test
    void bookIndexedEventRefreshesTheFilterValues() throws Exception {
        String url = startBroker(0);
        subscribe(url);
        awaitTrue(cache::isEnabled);
        assertEquals(6, cache.get().totalBooks());

        addBook(7);
        assertEquals(6, cache.get().totalBooks());
        publish(url, "{\"event\":\"BookIndexed\",\"version\":1,\"book_id\":7,\"terms\":10,"
                + "\"indexed_at\":\"2026-10-27T10:00:00Z\"}");

        awaitTrue(() -> totalBooks() == 7);
    }

    @Test
    void unreadableMessagesAlsoInvalidateAndDoNotStopTheSubscription() throws Exception {
        String url = startBroker(0);
        subscribe(url);
        awaitTrue(cache::isEnabled);
        cache.get();

        addBook(7);
        publish(url, "not json");
        awaitTrue(() -> totalBooks() == 7);

        addBook(8);
        publish(url, "{\"event\":\"BookIndexed\",\"version\":1,\"book_id\":8}");
        awaitTrue(() -> totalBooks() == 8);
    }

    @Test
    void cacheIsDisabledWhileTheBrokerIsDownAndResumesAfterwards() throws Exception {
        String url = startBroker(0);
        int port = URI.create(url).getPort();
        subscribe(url);
        awaitTrue(cache::isEnabled);

        stopBroker();
        awaitTrue(() -> !cache.isEnabled());
        addBook(7);
        assertEquals(7, cache.get().totalBooks());

        startBroker(port);
        awaitTrue(cache::isEnabled);
        addBook(8);
        publish(url, "{\"event\":\"BookIndexed\",\"version\":1,\"book_id\":8}");
        awaitTrue(() -> totalBooks() == 8);
    }

    @Test
    void startsAndClosesWithoutABroker() {
        subscriber = new BookIndexedSubscriber("tcp://127.0.0.1:1", cache);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            subscriber.start();
            subscriber.close();
        });
        assertFalse(cache.isEnabled());
    }

    private String startBroker(int port) throws Exception {
        broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setBrokerName("search-test-" + port);
        broker.addConnector("tcp://127.0.0.1:" + port);
        broker.start();
        broker.waitUntilStarted();
        return broker.getTransportConnectors().get(0).getPublishableConnectString();
    }

    private void stopBroker() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
            broker = null;
        }
    }

    private void subscribe(String url) {
        subscriber = new BookIndexedSubscriber(url, cache);
        subscriber.start();
    }

    private static void publish(String url, String text) throws Exception {
        try (Connection conn = new ActiveMQConnectionFactory(url).createConnection();
             Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageProducer producer = session.createProducer(session.createTopic(BookIndexedSubscriber.TOPIC_NAME))) {
            producer.send(session.createTextMessage(text));
        }
    }

    private int totalBooks() {
        try {
            return cache.get().totalBooks();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void addBook(int bookId) throws SQLException {
        try (java.sql.Connection conn = Sqlite.open(datamart); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO books VALUES (" + bookId + ", 'New', NULL, 'en', 2020, '2026-10-27T10:00:00Z')");
        }
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within " + WAIT);
            Thread.sleep(50);
        }
    }
}
