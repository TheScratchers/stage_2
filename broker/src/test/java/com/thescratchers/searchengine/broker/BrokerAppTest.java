package com.thescratchers.searchengine.broker;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerAppTest {

    @Test
    void testBrokerStartsAndAcceptsMessages(@TempDir Path tempDir) throws Exception {
        // Bind to port 0 so the OS assigns an available ephemeral port
        BrokerService broker = BrokerApp.start("tcp://127.0.0.1:0", tempDir.resolve("broker-test"));
        try {
            assertTrue(broker.isStarted());
            String connectUri = broker.getTransportConnectors().get(0).getPublishableConnectString();

            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(connectUri);
            try (Connection connection = factory.createConnection()) {
                connection.start();

                try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    Queue queue = session.createQueue("books.downloaded");

                    try (MessageProducer producer = session.createProducer(queue)) {
                        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                        TextMessage message = session.createTextMessage("{\"event\":\"BookDownloaded\",\"book_id\":1342}");
                        producer.send(message);
                    }

                    try (MessageConsumer consumer = session.createConsumer(queue)) {
                        Message received = consumer.receive(5000);
                        assertNotNull(received, "Expected to receive message from queue");
                        assertTrue(received instanceof TextMessage);
                        assertEquals("{\"event\":\"BookDownloaded\",\"book_id\":1342}", ((TextMessage) received).getText());
                    }
                }
            }
        } finally {
            broker.stop();
            broker.waitUntilStopped();
            assertTrue(broker.isStopped());
        }
    }

    @Test
    void testPersistenceDirectoryCreated(@TempDir Path tempDir) throws Exception {
        Path storageDir = tempDir.resolve("broker-storage");
        assertFalse(Files.exists(storageDir));

        BrokerService broker = BrokerApp.start("tcp://127.0.0.1:0", storageDir);
        try {
            assertTrue(Files.isDirectory(storageDir));
            assertTrue(Files.isDirectory(storageDir.resolve("kahadb")));
        } finally {
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    @Test
    void testRedeliveryAndDlq(@TempDir Path tempDir) throws Exception {
        BrokerService broker = BrokerApp.start("tcp://127.0.0.1:0", tempDir.resolve("broker-dlq-test"));
        try {
            String connectUri = broker.getTransportConnectors().get(0).getPublishableConnectString();
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(connectUri);

            // Configure client redelivery to match MAX_REDELIVERIES (5)
            RedeliveryPolicy redeliveryPolicy = factory.getRedeliveryPolicy();
            redeliveryPolicy.setMaximumRedeliveries(BrokerApp.MAX_REDELIVERIES);
            redeliveryPolicy.setInitialRedeliveryDelay(100L);
            redeliveryPolicy.setBackOffMultiplier(1.0);
            redeliveryPolicy.setUseExponentialBackOff(false);

            try (Connection connection = factory.createConnection()) {
                connection.start();

                // 1. Send one persistent message to test queue
                try (Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    Queue queue = producerSession.createQueue("books.downloaded");
                    try (MessageProducer producer = producerSession.createProducer(queue)) {
                        producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                        producer.send(producerSession.createTextMessage("poison-pill"));
                    }
                }

                // 2. Consume in transacted session and rollback 5 times (initial delivery + 5 redeliveries = 6 total deliveries)
                Queue queue = connection.createSession(false, Session.AUTO_ACKNOWLEDGE).createQueue("books.downloaded");
                for (int i = 0; i < BrokerApp.MAX_REDELIVERIES + 1; i++) {
                    try (Session transactedSession = connection.createSession(true, Session.SESSION_TRANSACTED)) {
                        try (MessageConsumer consumer = transactedSession.createConsumer(queue)) {
                            Message message = consumer.receive(5000);
                            assertNotNull(message, "Should receive message on delivery attempt " + (i + 1));
                            transactedSession.rollback();
                        }
                    }
                }

                // 3. The original queue should now be empty
                try (Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    try (MessageConsumer consumer = consumerSession.createConsumer(queue)) {
                        Message message = consumer.receive(1000);
                        assertEquals(null, message, "Original queue should be empty after exceeding max redeliveries");
                    }
                }

                // 4. Message must be moved to ActiveMQ.DLQ
                try (Session dlqSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    Queue dlq = dlqSession.createQueue("ActiveMQ.DLQ");
                    try (MessageConsumer dlqConsumer = dlqSession.createConsumer(dlq)) {
                        Message dlqMessage = dlqConsumer.receive(5000);
                        assertNotNull(dlqMessage, "Message should be present in ActiveMQ.DLQ");
                        assertTrue(dlqMessage instanceof TextMessage);
                        assertEquals("poison-pill", ((TextMessage) dlqMessage).getText());
                    }
                }
            }
        } finally {
            broker.stop();
            broker.waitUntilStopped();
        }
    }
}
