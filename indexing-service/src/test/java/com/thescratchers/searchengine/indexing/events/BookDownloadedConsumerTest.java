package com.thescratchers.searchengine.indexing.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.IngestionLogFixture;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import jakarta.jms.MessageConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the consumer against a real, non-persistent ActiveMQ broker on a free local port. */
class BookDownloadedConsumerTest {

    private static final long WAIT_MS = 30_000;

    @TempDir
    Path tempDir;

    private TestBroker broker;
    private DatamartStore store;
    private BookDownloadedConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        broker = new TestBroker(tempDir.resolve("broker"));
        store = new DatamartStore(tempDir.resolve("datamart.db"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void wrapsTheBrokerUrlInTheFailoverTransport() {
        assertEquals("failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=10000",
                Broker.failoverUrl("tcp://localhost:61616"));
        assertEquals("failover:(tcp://a:1,tcp://b:2)", Broker.failoverUrl("failover:(tcp://a:1,tcp://b:2)"));
    }

    @Test
    void rejectsLessThanOneWorker() {
        assertThrows(IllegalArgumentException.class,
                () -> new BookDownloadedConsumer(broker.url(), new BookIndexer(store), 0));
    }

    @Test
    void indexesTheBookAndThenPublishesBookIndexed() throws Exception {
        MessageConsumer indexed = broker.subscribeIndexed();
        start(1);

        broker.sendDownloaded(event(1342));

        JsonObject published = JsonParser.parseString(TestBroker.text(indexed.receive(WAIT_MS))).getAsJsonObject();
        assertEquals("BookIndexed", published.get("event").getAsString());
        assertEquals(1, published.get("version").getAsInt());
        assertEquals(1342, published.get("book_id").getAsInt());
        assertEquals(store.countPostings(1342), published.get("terms").getAsInt());
        assertTrue(published.get("indexed_at").getAsString().endsWith("Z"));
        assertTrue(store.booksForTerm("darcy").contains(1342));
    }

    @Test
    void duplicateEventsAreHarmless() throws Exception {
        MessageConsumer indexed = broker.subscribeIndexed();
        start(2);

        broker.sendDownloaded(event(1342));
        broker.sendDownloaded(event(1342));

        assertNotNull(indexed.receive(WAIT_MS));
        assertNotNull(indexed.receive(WAIT_MS));
        assertEquals(1, store.countBooks());
        assertEquals(store.booksForTerm("darcy"), List.of(1342));
    }

    @Test
    void aBookThatCannotBeIndexedIsRedeliveredAndEndsInTheDeadLetterQueue() throws Exception {
        MessageConsumer indexed = broker.subscribeIndexed();
        start(1);
        String missing = event(7, tempDir.resolve("7.header.txt"), tempDir.resolve("7.body.txt"));

        broker.sendDownloaded(missing);

        assertEquals(missing, broker.receiveDeadLetter(WAIT_MS));
        assertNull(indexed.receiveNoWait());
        assertEquals(0, store.countBooks());
    }

    @Test
    void unreadableMessagesEndInTheDeadLetterQueueWithoutStoppingTheWorker() throws Exception {
        MessageConsumer indexed = broker.subscribeIndexed();
        start(1);

        broker.sendDownloaded("not json");
        broker.sendDownloaded("{\"event\":\"BookDownloaded\",\"version\":1,\"book_id\":11}");
        broker.sendDownloaded(event(11));

        assertNotNull(indexed.receive(WAIT_MS));
        assertEquals(List.of(11), store.bookIds());
        Set<String> deadLetters = new HashSet<>();
        deadLetters.add(broker.receiveDeadLetter(WAIT_MS));
        deadLetters.add(broker.receiveDeadLetter(WAIT_MS));
        assertTrue(deadLetters.contains("not json"));
    }

    @Test
    void severalWorkersIndexTheWholeCorpus() throws Exception {
        MessageConsumer indexed = broker.subscribeIndexed();
        start(4);
        List<Integer> corpus = corpusIds();

        for (int bookId : corpus) {
            broker.sendDownloaded(event(bookId));
        }

        Set<Integer> published = new HashSet<>();
        for (int i = 0; i < corpus.size(); i++) {
            String text = TestBroker.text(indexed.receive(WAIT_MS));
            assertNotNull(text, "only " + published.size() + " BookIndexed events received");
            published.add(JsonParser.parseString(text).getAsJsonObject().get("book_id").getAsInt());
        }
        assertEquals(new HashSet<>(corpus), published);
        assertEquals(corpus.stream().sorted().toList(), store.bookIds());
    }

    @Test
    void startsAndClosesPromptlyWhileTheBrokerIsDown() throws Exception {
        String unreachable = broker.url();
        broker.close();
        broker = new TestBroker(tempDir.resolve("other-broker"));
        BookDownloadedConsumer offline = new BookDownloadedConsumer(unreachable, new BookIndexer(store), 2);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            offline.start();
            Thread.sleep(500);
            offline.close();
        });
    }

    private void start(int workers) {
        consumer = new BookDownloadedConsumer(broker.url(), new BookIndexer(store), workers);
        consumer.start();
    }

    private static String event(int bookId) {
        return event(bookId,
                IngestionLogFixture.SAMPLES.resolve(bookId + ".header.txt"),
                IngestionLogFixture.SAMPLES.resolve(bookId + ".body.txt"));
    }

    private static String event(int bookId, Path header, Path body) {
        JsonObject json = new JsonObject();
        json.addProperty("event", "BookDownloaded");
        json.addProperty("version", 1);
        json.addProperty("book_id", bookId);
        json.addProperty("header_path", header.toString());
        json.addProperty("body_path", body.toString());
        json.addProperty("ingested_at", "2026-10-28T10:00:00Z");
        return json.toString();
    }

    private static List<Integer> corpusIds() throws IOException {
        try (Stream<Path> files = Files.list(IngestionLogFixture.SAMPLES)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".header.txt"))
                    .map(name -> Integer.parseInt(name.substring(0, name.indexOf('.'))))
                    .toList();
        }
    }
}
