package com.thescratchers.searchengine.benchmarks.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thescratchers.searchengine.common.Sqlite;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The benchmark's own client of the broker, used to observe the choreography from outside.
 *
 * <ul>
 *   <li>It subscribes to the {@code books.indexed} topic, like a Search replica, and records when
 *       each {@code BookIndexed} event arrives.</li>
 *   <li>It can send {@code BookDownloaded} events to the {@code books.downloaded} queue, exactly as
 *       the Ingestion Service does, to load the Indexing Service without involving Ingestion.</li>
 * </ul>
 */
public final class BrokerProbe implements AutoCloseable {

    /** A book announced as available, with the paths recorded by the Ingestion Service. */
    public record AvailableBook(int bookId, String headerPath, String bodyPath) {
    }

    private final Connection connection;
    private final Session publishSession;
    private final MessageProducer producer;
    private final AtomicInteger indexedEvents = new AtomicInteger();
    private final ConcurrentHashMap<Integer, Long> lastIndexedNanos = new ConcurrentHashMap<>();

    /** Connects to {@code brokerUrl} and starts listening to {@code books.indexed}. */
    public BrokerProbe(String brokerUrl) throws JMSException {
        connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
        Session listenSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = listenSession.createConsumer(listenSession.createTopic("books.indexed"));
        consumer.setMessageListener(message -> {
            long now = System.nanoTime();
            try {
                JsonObject event = JsonParser.parseString(((TextMessage) message).getText()).getAsJsonObject();
                lastIndexedNanos.put(event.get("book_id").getAsInt(), now);
                indexedEvents.incrementAndGet();
            } catch (JMSException | RuntimeException e) {
                // Not a BookIndexed event; ignored.
            }
        });
        publishSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        producer = publishSession.createProducer(publishSession.createQueue("books.downloaded"));
        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
        connection.start();
    }

    /** Number of {@code BookIndexed} events received so far. */
    public int indexedEvents() {
        return indexedEvents.get();
    }

    /** Arrival time ({@link System#nanoTime()}) of the last {@code BookIndexed} of {@code bookId}, or {@code null}. */
    public Long lastIndexed(int bookId) {
        return lastIndexedNanos.get(bookId);
    }

    /** Sends one {@code BookDownloaded} event, as the Ingestion Service publishes it. */
    public synchronized void sendDownloaded(AvailableBook book) throws JMSException {
        JsonObject event = new JsonObject();
        event.addProperty("event", "BookDownloaded");
        event.addProperty("version", 1);
        event.addProperty("book_id", book.bookId());
        event.addProperty("header_path", book.headerPath());
        event.addProperty("body_path", book.bodyPath());
        event.addProperty("ingested_at", Instant.now().toString());
        producer.send(publishSession.createTextMessage(event.toString()));
    }

    /** Waits until at least {@code count} {@code BookIndexed} events have arrived. */
    public void awaitIndexedEvents(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (indexedEvents.get() < count) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("only " + indexedEvents.get() + " of " + count + " BookIndexed events arrived");
            }
            Thread.sleep(2);
        }
    }

    /** Waits for a {@code BookIndexed} of {@code bookId} received after {@code sinceNanos}; returns its arrival time. */
    public long awaitIndexed(int bookId, long sinceNanos, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Long arrived = lastIndexedNanos.get(bookId);
            if (arrived != null && arrived >= sinceNanos) {
                return arrived;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("no BookIndexed for book " + bookId);
            }
            Thread.sleep(1);
        }
    }

    /** Reads the available books from the ingestion log, read-only, as the Indexing Service does. */
    public static List<AvailableBook> availableBooks(Path ingestionDb) throws SQLException {
        List<AvailableBook> books = new ArrayList<>();
        try (var conn = Sqlite.openReadOnly(ingestionDb);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT book_id, header_path, body_path FROM ingestion_log WHERE status = 'available' ORDER BY book_id");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                books.add(new AvailableBook(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        }
        return books;
    }

    @Override
    public void close() throws JMSException {
        connection.close();
    }
}
