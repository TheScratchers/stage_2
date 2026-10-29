package com.thescratchers.searchengine.ingestion;

import com.thescratchers.searchengine.ingestion.broker.BookDownloadedPublisher;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeLayout;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeWriter;
import com.thescratchers.searchengine.ingestion.datalake.IngestionLogStore;
import com.thescratchers.searchengine.ingestion.datalake.IngestionRecord;
import com.thescratchers.searchengine.ingestion.gutenberg.DownloadException;
import com.thescratchers.searchengine.ingestion.gutenberg.GutenbergClient;
import io.javalin.Javalin;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");

    @TempDir
    Path tempDir;

    private Javalin fakeGutenberg;
    private BookDownloader downloader;
    private IngestionLogStore store;
    private BookDownloadedPublisher publisher;
    private ActiveMQConnectionFactory brokerFactory;
    private IngestionService service;

    @BeforeEach
    void setUp() throws Exception {
        String pride = SampleBooks.rawText(1342);
        fakeGutenberg = Javalin.create()
                .get("/cache/epub/1342/pg1342.txt", ctx -> ctx.result(pride))
                .get("/cache/epub/5/pg5.txt", ctx -> ctx.status(500))
                .start(0);

        GutenbergClient client = new GutenbergClient("http://localhost:" + fakeGutenberg.port(), Duration.ofSeconds(2));
        DatalakeWriter writer = new DatalakeWriter(tempDir.resolve("datalake"), DatalakeLayout.TIME_BASED);
        downloader = new BookDownloader(client, writer, Clock.fixed(NOW, ZoneOffset.UTC));

        store = new IngestionLogStore(tempDir.resolve("datalake/ingestion.db"));

        brokerFactory = new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
        publisher = new BookDownloadedPublisher(brokerFactory);

        service = new IngestionService(downloader, store, publisher);
    }

    @AfterEach
    void tearDown() {
        if (fakeGutenberg != null) {
            fakeGutenberg.stop();
        }
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    void testIngestNewBookPublishesEventAndRecordsStore() throws Exception {
        IngestResult result = service.ingest(1342);

        assertEquals(1342, result.bookId());
        assertEquals(IngestResult.STATUS_DOWNLOADED, result.status());
        assertTrue(result.path().contains("1342"));
        assertTrue(Path.of(result.path()).isAbsolute(), "Result path must be absolute");

        // Store verification
        Optional<IngestionRecord> record = store.findById(1342);
        assertTrue(record.isPresent());
        assertTrue(record.get().isAvailable());
        assertTrue(Path.of(record.get().headerPath()).isAbsolute(), "Header path in DB must be absolute");
        assertTrue(Path.of(record.get().bodyPath()).isAbsolute(), "Body path in DB must be absolute");
        assertEquals(Optional.of("available"), service.getStatus(1342));
        assertEquals(List.of(1342), service.listAvailable());

        // Broker verification
        try (Connection conn = brokerFactory.createConnection()) {
            conn.start();
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue(BookDownloadedPublisher.QUEUE_NAME);
                try (MessageConsumer consumer = session.createConsumer(queue)) {
                    Message msg = consumer.receive(2000);
                    assertNotNull(msg, "ActiveMQ event should be published");
                    assertTrue(msg instanceof TextMessage);
                    String body = ((TextMessage) msg).getText();
                    assertTrue(body.contains("\"event\":\"BookDownloaded\""));
                    assertTrue(body.contains("\"book_id\":1342"));
                    assertTrue(body.contains("\"header_path\":\"" + record.get().headerPath().replace("\\", "\\\\") + "\"")
                            || body.contains(record.get().headerPath()));
                }
            }
        }
    }

    @Test
    void testIngestAlreadyAvailableIsIdempotentAndRepublishesEvent() throws Exception {
        // First ingest
        IngestResult first = service.ingest(1342);
        assertEquals(IngestResult.STATUS_DOWNLOADED, first.status());

        // Consume the first event from broker
        try (Connection conn = brokerFactory.createConnection()) {
            conn.start();
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue(BookDownloadedPublisher.QUEUE_NAME);
                try (MessageConsumer consumer = session.createConsumer(queue)) {
                    Message msg1 = consumer.receive(2000);
                    assertNotNull(msg1, "First event should be on broker");

                    // Second ingest (should be idempotent: no re-download, but re-publishes event)
                    IngestResult second = service.ingest(1342);
                    assertEquals(1342, second.bookId());
                    assertEquals(IngestResult.STATUS_ALREADY_AVAILABLE, second.status());
                    assertEquals(first.path(), second.path());

                    // Verify second event was published
                    Message msg2 = consumer.receive(2000);
                    assertNotNull(msg2, "Re-ingestion must republish the BookDownloaded event");
                    assertTrue(((TextMessage) msg2).getText().contains("\"book_id\":1342"));
                }
            }
        }
    }

    @Test
    void testIngestBrokerFailureSavesInStoreAndThrowsJMSException() throws Exception {
        ActiveMQConnectionFactory brokenFactory = new ActiveMQConnectionFactory("tcp://localhost:60001?timeout=500");
        BookDownloadedPublisher brokenPublisher = new BookDownloadedPublisher(brokenFactory);
        IngestionService serviceWithBrokenBroker = new IngestionService(downloader, store, brokenPublisher);

        try {
            assertThrows(JMSException.class, () -> serviceWithBrokenBroker.ingest(1342));

            // Book must remain available in store despite broker publish failure
            Optional<IngestionRecord> record = store.findById(1342);
            assertTrue(record.isPresent(), "Book must be saved in ingestion_log even if broker fails");
            assertTrue(record.get().isAvailable());

            // When publisher is repaired, ingesting again successfully publishes the event
            IngestResult retry = service.ingest(1342);
            assertEquals(IngestResult.STATUS_ALREADY_AVAILABLE, retry.status());
        } finally {
            brokenPublisher.close();
        }
    }

    @Test
    void testIngestInvalidBookId() {
        assertThrows(IllegalArgumentException.class, () -> service.ingest(0));
        assertThrows(IllegalArgumentException.class, () -> service.ingest(-5));
        assertThrows(IllegalArgumentException.class, () -> service.getStatus(0));
    }

    @Test
    void testIngestFailedDownloadRecordsFailure() throws Exception {
        assertThrows(DownloadException.class, () -> service.ingest(5));

        assertEquals(Optional.of("failed"), service.getStatus(5));
        assertTrue(service.listAvailable().isEmpty());
    }
}

