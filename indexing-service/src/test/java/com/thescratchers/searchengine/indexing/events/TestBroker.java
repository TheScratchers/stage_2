package com.thescratchers.searchengine.indexing.events;

import com.google.gson.JsonObject;
import com.thescratchers.searchengine.indexing.IngestionLogFixture;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.broker.region.Destination;
import org.apache.activemq.broker.region.policy.RedeliveryPolicyMap;
import org.apache.activemq.broker.util.RedeliveryPlugin;
import org.apache.activemq.command.ActiveMQQueue;

import java.nio.file.Path;

/**
 * Non-persistent ActiveMQ broker for tests, on a free local port, with the same redelivery policy
 * as the project's broker ({@code BrokerApp}: 5 broker-side redeliveries, then {@code ActiveMQ.DLQ})
 * but with shorter delays. All its files go to {@code dataDir}.
 */
final class TestBroker implements AutoCloseable {

    static final String DLQ = "ActiveMQ.DLQ";
    private static final long REDELIVERY_DELAY_MS = 1100;

    private final BrokerService broker;
    private final String url;
    private final Connection client;
    private final Session session;

    TestBroker(Path dataDir) throws Exception {
        broker = new BrokerService();
        broker.setBrokerName("indexing-test-broker");
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setSchedulerSupport(true);
        broker.setDataDirectoryFile(dataDir.toFile());
        broker.setTmpDataDirectory(dataDir.resolve("tmp").toFile());

        // RedeliveryPlugin rejects delays of 1000 ms or less.
        RedeliveryPolicy policy = new RedeliveryPolicy();
        policy.setMaximumRedeliveries(5);
        policy.setInitialRedeliveryDelay(REDELIVERY_DELAY_MS);
        policy.setRedeliveryDelay(REDELIVERY_DELAY_MS);
        RedeliveryPolicyMap policies = new RedeliveryPolicyMap();
        policies.setDefaultEntry(policy);
        RedeliveryPlugin redelivery = new RedeliveryPlugin();
        redelivery.setRedeliveryPolicyMap(policies);
        broker.setPlugins(new BrokerPlugin[] {redelivery});

        TransportConnector connector = broker.addConnector("tcp://localhost:0");
        broker.start();
        broker.waitUntilStarted();
        url = connector.getPublishableConnectString();

        client = new ActiveMQConnectionFactory(url).createConnection();
        client.start();
        session = client.createSession(false, Session.AUTO_ACKNOWLEDGE);
    }

    /** {@code tcp://...} URL of the broker. */
    String url() {
        return url;
    }

    /** Sends a persistent text message to the {@code books.downloaded} queue. */
    void sendDownloaded(String text) throws JMSException {
        try (MessageProducer producer = session.createProducer(session.createQueue(Broker.DOWNLOADED_QUEUE))) {
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(session.createTextMessage(text));
        }
    }

    /** Sends the {@code BookDownloaded} event of a book of the bundled corpus. */
    void sendDownloaded(int bookId) throws JMSException {
        sendDownloaded(downloadedEvent(bookId));
    }

    /**
     * Messages of the {@code books.downloaded} queue that have not been acknowledged yet, including
     * those delivered to a consumer whose transaction is still open.
     */
    long pendingDownloaded() throws Exception {
        return queue(Broker.DOWNLOADED_QUEUE).getDestinationStatistics().getMessages().getCount();
    }

    /** Consumers currently attached to the {@code books.downloaded} queue (one per worker thread). */
    int downloadedConsumers() throws Exception {
        return queue(Broker.DOWNLOADED_QUEUE).getConsumers().size();
    }

    /** Messages waiting in the dead-letter queue. */
    long deadLetterCount() throws Exception {
        return queue(DLQ).getDestinationStatistics().getMessages().getCount();
    }

    private Destination queue(String name) throws Exception {
        return broker.getDestination(new ActiveMQQueue(name));
    }

    /** JSON of the {@code BookDownloaded} event of a corpus book, with absolute paths as Ingestion sends them. */
    static String downloadedEvent(int bookId) {
        return downloadedEvent(bookId,
                IngestionLogFixture.SAMPLES.resolve(bookId + ".header.txt").toAbsolutePath(),
                IngestionLogFixture.SAMPLES.resolve(bookId + ".body.txt").toAbsolutePath());
    }

    /** JSON of a {@code BookDownloaded} event with explicit file paths. */
    static String downloadedEvent(int bookId, Path header, Path body) {
        JsonObject json = new JsonObject();
        json.addProperty("event", "BookDownloaded");
        json.addProperty("version", 1);
        json.addProperty("book_id", bookId);
        json.addProperty("header_path", header.toString());
        json.addProperty("body_path", body.toString());
        json.addProperty("ingested_at", "2026-10-28T10:00:00Z");
        return json.toString();
    }

    /** Subscribes to the {@code books.indexed} topic; call it before the events are published. */
    MessageConsumer subscribeIndexed() throws JMSException {
        return session.createConsumer(session.createTopic(Broker.INDEXED_TOPIC));
    }

    /** Returns the next message of the dead-letter queue, or {@code null} after {@code timeoutMs}. */
    String receiveDeadLetter(long timeoutMs) throws JMSException {
        try (MessageConsumer consumer = session.createConsumer(session.createQueue(DLQ))) {
            return text(consumer.receive(timeoutMs));
        }
    }

    /** Text of a message, or {@code null} if there is none. */
    static String text(Message message) throws JMSException {
        return message == null ? null : ((TextMessage) message).getText();
    }

    @Override
    public void close() throws Exception {
        client.close();
        broker.stop();
        broker.waitUntilStopped();
    }
}
