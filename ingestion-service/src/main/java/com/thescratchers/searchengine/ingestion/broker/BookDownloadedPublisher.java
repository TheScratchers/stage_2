package com.thescratchers.searchengine.ingestion.broker;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thescratchers.searchengine.common.Config;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Publishes persistent {@link BookDownloadedEvent} messages to the {@code books.downloaded} ActiveMQ queue.
 *
 * <p>Thread-safe for publishing: manages connection lifecycle lazily, reconnecting if disconnected,
 * and creates short-lived sessions per send.
 */
public final class BookDownloadedPublisher implements AutoCloseable {

    public static final String QUEUE_NAME = "books.downloaded";

    private static final Logger LOG = LoggerFactory.getLogger(BookDownloadedPublisher.class);
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private final ConnectionFactory connectionFactory;
    private Connection connection;

    public BookDownloadedPublisher(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    public static BookDownloadedPublisher fromConfig() {
        String brokerUrl = Config.get("BROKER_URL", "tcp://localhost:61616");
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        return new BookDownloadedPublisher(factory);
    }

    /**
     * Sends a persistent {@link BookDownloadedEvent} to the {@code books.downloaded} queue.
     *
     * @param event the event to publish
     * @throws JMSException if connecting or publishing to the broker fails
     */
    public void publish(BookDownloadedEvent event) throws JMSException {
        Connection conn = getOrCreateConnection();
        try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Queue queue = session.createQueue(QUEUE_NAME);
            try (MessageProducer producer = session.createProducer(queue)) {
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                String json = GSON.toJson(event);
                TextMessage message = session.createTextMessage(json);
                producer.send(message);
                LOG.info("Published BookDownloaded event for book {} to queue {}", event.bookId(), QUEUE_NAME);
            }
        } catch (JMSException e) {
            resetConnection();
            throw e;
        }
    }

    private synchronized Connection getOrCreateConnection() throws JMSException {
        if (connection == null) {
            connection = connectionFactory.createConnection();
            connection.start();
        }
        return connection;
    }

    private synchronized void resetConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
            connection = null;
        }
    }

    @Override
    public synchronized void close() {
        resetConnection();
    }
}

