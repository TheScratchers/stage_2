package com.thescratchers.searchengine.indexing.events;

import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.BookIndexer.IndexResult;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumes {@code BookDownloaded} events from the {@code books.downloaded} queue, indexes each book
 * and publishes {@code BookIndexed} on the {@code books.indexed} topic.
 *
 * <p><b>Threads.</b> {@code workers} threads compete for the queue, each with its own JMS session
 * (sessions are single-threaded) and its own SQLite connections; SQLite serialises their commits.
 * The queue prefetch is 1, so a busy worker never holds messages that an idle worker or another
 * replica could take.
 *
 * <p><b>Acknowledgement.</b> Each worker uses a transacted session. A message is handled as:
 * index the book and commit it in SQLite, send {@code BookIndexed}, then commit the JMS session,
 * which acknowledges the message and releases the event in one step. If anything fails, the session
 * is rolled back: nothing is published and the message goes back to the broker, which redelivers it
 * up to 5 times and then moves it to {@code ActiveMQ.DLQ} (broker-side redelivery policy). The
 * client-side redelivery is disabled so that the broker is the only one counting attempts. If the
 * process stops between the SQLite commit and the JMS commit, the message is delivered again and
 * re-indexing it is harmless, because indexing a book is idempotent.
 *
 * <p>The connection uses the {@code failover:} transport: the consumer starts even if the broker is
 * down and resumes after a broker restart.
 */
public final class BookDownloadedConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BookDownloadedConsumer.class);
    private static final long RECEIVE_TIMEOUT_MS = 1000;

    private final ActiveMQConnectionFactory factory;
    private final BookIndexer indexer;
    private final int workers;
    private final Thread connector;
    private final List<Thread> workerThreads = new ArrayList<>();
    private volatile Connection connection;
    private volatile boolean closed;

    /**
     * @param brokerUrl broker URL ({@code BROKER_URL}); it is wrapped in {@code failover:(...)}
     * @param indexer   indexer shared by all workers (it is thread-safe)
     * @param workers   number of consumer threads ({@code INDEXING_WORKERS}), at least 1
     */
    public BookDownloadedConsumer(String brokerUrl, BookIndexer indexer, int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("INDEXING_WORKERS must be at least 1 but was " + workers);
        }
        this.factory = new ActiveMQConnectionFactory(Broker.failoverUrl(brokerUrl));
        this.factory.getRedeliveryPolicy().setMaximumRedeliveries(0);
        this.factory.getPrefetchPolicy().setQueuePrefetch(1);
        this.indexer = indexer;
        this.workers = workers;
        this.connector = new Thread(this::connect, "indexing-consumer-connector");
        this.connector.setDaemon(true);
    }

    /** Starts connecting in the background and then the workers; it returns immediately. */
    public void start() {
        connector.start();
    }

    private void connect() {
        try {
            Connection conn = factory.createConnection();
            connection = conn;
            if (closed) {
                conn.close();
                return;
            }
            conn.start();
            for (int i = 1; i <= workers; i++) {
                Thread worker = new Thread(() -> work(conn), "indexing-worker-" + i);
                worker.setDaemon(true);
                workerThreads.add(worker);
                worker.start();
            }
            LOG.info("Consuming queue {} with {} worker(s)", Broker.DOWNLOADED_QUEUE, workers);
        } catch (JMSException e) {
            if (!closed) {
                LOG.error("Cannot connect to the broker; BookDownloaded events will not be consumed", e);
            }
        }
    }

    private void work(Connection conn) {
        try (Session session = conn.createSession(true, Session.SESSION_TRANSACTED);
             MessageConsumer consumer = session.createConsumer(session.createQueue(Broker.DOWNLOADED_QUEUE));
             MessageProducer producer = session.createProducer(session.createTopic(Broker.INDEXED_TOPIC))) {
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            while (!closed) {
                Message message = consumer.receive(RECEIVE_TIMEOUT_MS);
                if (message != null) {
                    handle(session, producer, message);
                }
            }
        } catch (JMSException e) {
            if (!closed) {
                LOG.error("Worker {} stopped because of a broker error", Thread.currentThread().getName(), e);
            }
        }
    }

    /** Processes one message inside the session's transaction; see the class comment. */
    private void handle(Session session, MessageProducer producer, Message message) throws JMSException {
        try {
            BookDownloadedEvent event = parse(message);
            IndexResult result = indexer.index(event.bookId(), Path.of(event.headerPath()), Path.of(event.bodyPath()));
            producer.send(session.createTextMessage(Broker.GSON.toJson(BookIndexedEvent.of(result))));
            session.commit();
        } catch (IOException | SQLException | JMSException | RuntimeException e) {
            LOG.warn("Could not process message {} (delivery {}); it is returned to the broker: {}",
                    message.getJMSMessageID(), deliveryCount(message), e.getMessage());
            session.rollback();
        }
    }

    private static BookDownloadedEvent parse(Message message) throws JMSException {
        if (!(message instanceof TextMessage text)) {
            throw new IllegalArgumentException("expected a text message but got " + message.getClass().getSimpleName());
        }
        BookDownloadedEvent event = Broker.GSON.fromJson(text.getText(), BookDownloadedEvent.class);
        if (event == null) {
            throw new IllegalArgumentException("empty message");
        }
        event.validate();
        return event;
    }

    private static int deliveryCount(Message message) {
        try {
            return message.propertyExists("JMSXDeliveryCount") ? message.getIntProperty("JMSXDeliveryCount") : 1;
        } catch (JMSException e) {
            return 1;
        }
    }

    /** Stops the workers after their current message and closes the connection. */
    @Override
    public void close() {
        closed = true;
        Connection conn = connection;
        if (conn == null) {
            connector.interrupt();
            return;
        }
        for (Thread worker : workerThreads) {
            try {
                worker.join(RECEIVE_TIMEOUT_MS * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            conn.close();
        } catch (JMSException e) {
            LOG.warn("Error closing the broker connection", e);
        }
    }
}
