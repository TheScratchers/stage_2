package com.thescratchers.searchengine.ingestion.broker;

import com.google.gson.Gson;
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

/**
 * Publishes persistent {@link BookDownloadedEvent} messages to the {@code books.downloaded} ActiveMQ queue.
 *
 * <p>Thread-safe for publishing: creates short-lived sessions per send while keeping a shared JMS connection.
 */
public final class BookDownloadedPublisher implements AutoCloseable {

    public static final String QUEUE_NAME = "books.downloaded";

    private static final Logger LOG = LoggerFactory.getLogger(BookDownloadedPublisher.class);
    private static final Gson GSON = new Gson();

    private final Connection connection;

    public BookDownloadedPublisher(ConnectionFactory connectionFactory) throws JMSException {
        this.connection = connectionFactory.createConnection();
        this.connection.start();
    }

    public static BookDownloadedPublisher fromConfig() throws JMSException {
        String brokerUrl = Config.get("BROKER_URL", "tcp://localhost:61616");
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        return new BookDownloadedPublisher(factory);
    }

    /**
     * Sends a persistent {@link BookDownloadedEvent} to the {@code books.downloaded} queue.
     *
     * @param event the event to publish
     * @throws JMSException if publishing to the broker fails
     */
    public void publish(BookDownloadedEvent event) throws JMSException {
        try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Queue queue = session.createQueue(QUEUE_NAME);
            try (MessageProducer producer = session.createProducer(queue)) {
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                String json = GSON.toJson(event);
                TextMessage message = session.createTextMessage(json);
                producer.send(message);
                LOG.info("Published BookDownloaded event for book {} to queue {}", event.book_id(), QUEUE_NAME);
            }
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (JMSException e) {
            LOG.warn("Error closing JMS connection", e);
        }
    }
}
