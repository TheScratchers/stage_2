package com.thescratchers.searchengine.search.events;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.thescratchers.searchengine.search.cache.FilterValuesCache;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.transport.TransportListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Subscribes the Search Service to the {@code books.indexed} topic and invalidates the
 * {@link FilterValuesCache} whenever the Indexing Service commits a book.
 *
 * <p>The topic is not durable: every Search replica receives every event while it is connected,
 * and events published while it is disconnected are not needed, because the cache is disabled for
 * that time and is reloaded from the datamart once the subscription is back. The connection uses
 * ActiveMQ's {@code failover:} transport, so it is established in the background and re-established
 * automatically after a broker restart; Search keeps answering requests meanwhile.
 */
public final class BookIndexedSubscriber implements AutoCloseable {

    /** Topic on which the Indexing Service publishes {@link BookIndexedEvent}s. */
    public static final String TOPIC_NAME = "books.indexed";

    private static final Logger LOG = LoggerFactory.getLogger(BookIndexedSubscriber.class);
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private final ActiveMQConnectionFactory factory;
    private final FilterValuesCache cache;
    private final Thread connector;

    private volatile ActiveMQConnection connection;
    private volatile boolean closed;

    /**
     * @param brokerUrl broker URL, e.g. {@code tcp://localhost:61616}; it is wrapped in
     *                  {@code failover:(...)} unless it already uses that transport
     * @param cache     cache invalidated on every event
     */
    public BookIndexedSubscriber(String brokerUrl, FilterValuesCache cache) {
        this.factory = new ActiveMQConnectionFactory(failoverUrl(brokerUrl));
        this.cache = cache;
        this.connector = new Thread(this::subscribe, "book-indexed-subscriber");
        this.connector.setDaemon(true);
    }

    /** Wraps {@code brokerUrl} in the failover transport, which reconnects with a growing delay. */
    static String failoverUrl(String brokerUrl) {
        if (brokerUrl.startsWith("failover:")) {
            return brokerUrl;
        }
        return "failover:(" + brokerUrl + ")?initialReconnectDelay=1000&maxReconnectDelay=10000";
    }

    /** Starts connecting in the background; it returns immediately even if the broker is down. */
    public void start() {
        connector.start();
    }

    private void subscribe() {
        try {
            ActiveMQConnection conn = (ActiveMQConnection) factory.createConnection();
            connection = conn;
            if (closed) {
                conn.close();
                return;
            }
            conn.addTransportListener(new ConnectionStateListener());
            // Blocks until the broker is reachable (failover transport).
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createTopic(TOPIC_NAME));
            consumer.setMessageListener(this::onMessage);
            conn.start();
            cache.enable();
            LOG.info("Subscribed to topic {}; filter values are cached until the next BookIndexed event", TOPIC_NAME);
        } catch (JMSException e) {
            if (!closed) {
                LOG.error("Cannot subscribe to topic {}; filter values will be read from the datamart on every request",
                        TOPIC_NAME, e);
            }
        }
    }

    private void onMessage(Message message) {
        cache.invalidate();
        try {
            if (message instanceof TextMessage text) {
                BookIndexedEvent event = GSON.fromJson(text.getText(), BookIndexedEvent.class);
                if (event != null && BookIndexedEvent.NAME.equals(event.event())) {
                    LOG.debug("Book {} indexed at {}; filter values invalidated", event.bookId(), event.indexedAt());
                    return;
                }
            }
            LOG.warn("Unexpected message on topic {}: {}", TOPIC_NAME, message);
        } catch (JMSException | JsonParseException e) {
            LOG.warn("Unreadable message on topic {}", TOPIC_NAME, e);
        }
    }

    /** Stops the subscription; the cache is disabled because events are no longer received. */
    @Override
    public void close() {
        closed = true;
        ActiveMQConnection conn = connection;
        if (conn == null) {
            connector.interrupt();
        } else {
            try {
                conn.close();
            } catch (JMSException e) {
                LOG.warn("Error closing the broker connection", e);
            }
        }
        cache.disable();
    }

    /** Disables the cache while the connection to the broker is interrupted. */
    private final class ConnectionStateListener implements TransportListener {

        @Override
        public void onCommand(Object command) {
        }

        @Override
        public void onException(IOException error) {
            LOG.warn("Broker connection failed", error);
        }

        @Override
        public void transportInterupted() {
            cache.disable();
            LOG.warn("Lost the connection to the broker; filter values are read from the datamart until it is back");
        }

        @Override
        public void transportResumed() {
            if (!closed) {
                cache.enable();
                LOG.info("Connection to the broker restored; filter values are cached again");
            }
        }
    }
}
