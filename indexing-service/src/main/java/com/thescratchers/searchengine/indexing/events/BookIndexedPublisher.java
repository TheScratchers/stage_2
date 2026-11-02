package com.thescratchers.searchengine.indexing.events;

import com.thescratchers.searchengine.indexing.BookIndexer.IndexResult;
import com.thescratchers.searchengine.indexing.service.IndexListener;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes {@code BookIndexed} on the {@code books.indexed} topic for books indexed through the
 * administrative HTTP endpoints, so that Search refreshes its cached filter values as it does for
 * books indexed from the queue.
 *
 * <p>It runs inside an HTTP request after the book has been committed, so it must neither fail
 * nor block that request. Unlike the queue consumer it therefore does not use the
 * {@code failover:} transport, which waits for the broker to come back: it connects directly,
 * gives up after {@value #TIMEOUT_MS} ms, logs the problem and drops the connection so that the
 * next publication tries again. Search does not lose correctness, only the freshness of its filter
 * cache until the next event. Thread-safe: publications are serialised on the shared connection.
 */
public final class BookIndexedPublisher implements IndexListener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BookIndexedPublisher.class);
    private static final int TIMEOUT_MS = 3000;

    private final ActiveMQConnectionFactory factory;
    private Connection connection;

    /** @param brokerUrl broker URL ({@code BROKER_URL}), e.g. {@code tcp://localhost:61616} */
    public BookIndexedPublisher(String brokerUrl) {
        this.factory = new ActiveMQConnectionFactory(brokerUrl);
        this.factory.setConnectResponseTimeout(TIMEOUT_MS);
        this.factory.setSendTimeout(TIMEOUT_MS);
    }

    @Override
    public synchronized void onIndexed(IndexResult result) {
        try {
            if (connection == null) {
                Connection conn = factory.createConnection();
                conn.start();
                connection = conn;
            }
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageProducer producer = session.createProducer(session.createTopic(Broker.INDEXED_TOPIC))) {
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.send(session.createTextMessage(Broker.GSON.toJson(BookIndexedEvent.of(result))));
            }
        } catch (JMSException e) {
            LOG.warn("BookIndexed for book {} was not published; Search refreshes its filters on the next event: {}",
                    result.bookId(), e.getMessage());
            close();
        }
    }

    /** Closes the connection, if it is open; a later publication opens a new one. */
    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException e) {
                LOG.debug("Error closing the broker connection", e);
            }
            connection = null;
        }
    }
}
