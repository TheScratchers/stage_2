package com.thescratchers.searchengine.ingestion.broker;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookDownloadedPublisherTest {

    @Test
    void testPublishEventToQueue() throws Exception {
        String brokerUrl = "vm://localhost?broker.persistent=false";
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);

        try (BookDownloadedPublisher publisher = new BookDownloadedPublisher(factory)) {
            Instant now = Instant.parse("2026-10-26T14:00:00Z");
            BookDownloadedEvent event = BookDownloadedEvent.of(1342, "datalake/1342.header.txt", "datalake/1342.body.txt", now);

            publisher.publish(event);

            // Read back from queue using a test consumer
            try (Connection conn = factory.createConnection()) {
                conn.start();
                try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    Queue queue = session.createQueue(BookDownloadedPublisher.QUEUE_NAME);
                    try (MessageConsumer consumer = session.createConsumer(queue)) {
                        Message received = consumer.receive(3000);
                        assertNotNull(received, "Message should be in queue");
                        assertTrue(received instanceof TextMessage);
                        String text = ((TextMessage) received).getText();
                        assertTrue(text.contains("\"event\":\"BookDownloaded\""));
                        assertTrue(text.contains("\"book_id\":1342"));
                        assertTrue(text.contains("\"header_path\":\"datalake/1342.header.txt\""));
                        assertTrue(text.contains("\"body_path\":\"datalake/1342.body.txt\""));
                        assertTrue(text.contains("\"ingested_at\":\"2026-10-26T14:00:00Z\""));
                    }
                }
            }
        }
    }
}
