package com.thescratchers.searchengine.ingestion;

import com.thescratchers.searchengine.ingestion.broker.BookDownloadedPublisher;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeLayout;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeWriter;
import com.thescratchers.searchengine.ingestion.datalake.IngestionLogStore;
import com.thescratchers.searchengine.ingestion.gutenberg.GutenbergClient;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionAppTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");

    @TempDir
    Path tempDir;

    private Javalin fakeGutenberg;
    private BookDownloadedPublisher publisher;
    private Javalin app;
    private HttpClient http;
    private String baseUri;

    @BeforeEach
    void setUp() throws Exception {
        String pride = SampleBooks.rawText(1342);
        fakeGutenberg = Javalin.create()
                .get("/cache/epub/1342/pg1342.txt", ctx -> ctx.result(pride))
                .get("/cache/epub/5/pg5.txt", ctx -> ctx.status(500))
                .start(0);

        GutenbergClient client = new GutenbergClient("http://localhost:" + fakeGutenberg.port(), Duration.ofSeconds(2));
        DatalakeWriter writer = new DatalakeWriter(tempDir.resolve("datalake"), DatalakeLayout.TIME_BASED);
        BookDownloader downloader = new BookDownloader(client, writer, Clock.fixed(NOW, ZoneOffset.UTC));
        IngestionLogStore store = new IngestionLogStore(tempDir.resolve("datalake/ingestion.db"));

        ActiveMQConnectionFactory brokerFactory = new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
        publisher = new BookDownloadedPublisher(brokerFactory);

        IngestionService service = new IngestionService(downloader, store, publisher);
        app = IngestionApp.start(0, service);
        http = HttpClient.newHttpClient();
        baseUri = "http://localhost:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (fakeGutenberg != null) {
            fakeGutenberg.stop();
        }
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    void testPostIngestSuccessAndIdempotency() throws Exception {
        // 1. Initial ingestion
        HttpRequest req1 = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/1342"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp1 = http.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp1.statusCode());
        assertTrue(resp1.body().contains("\"book_id\":1342"));
        assertTrue(resp1.body().contains("\"status\":\"downloaded\""));

        // 2. Query status: should be available
        HttpRequest reqStatus = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/status/1342")).GET().build();
        HttpResponse<String> respStatus = http.send(reqStatus, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respStatus.statusCode());
        assertTrue(respStatus.body().contains("\"book_id\":1342"));
        assertTrue(respStatus.body().contains("\"status\":\"available\""));

        // 3. Repeated ingestion (idempotent)
        HttpResponse<String> resp2 = http.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp2.statusCode());
        assertTrue(resp2.body().contains("\"book_id\":1342"));
        assertTrue(resp2.body().contains("\"status\":\"already_available\""));

        // 4. Ingest list should contain 1342
        HttpRequest reqList = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/list")).GET().build();
        HttpResponse<String> respList = http.send(reqList, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respList.statusCode());
        assertTrue(respList.body().contains("\"count\":1"));
        assertTrue(respList.body().contains("\"books\":[1342]"));
    }

    @Test
    void testPostIngestInvalidParameters() throws Exception {
        HttpRequest req1 = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/abc"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp1 = http.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp1.statusCode());
        assertTrue(resp1.body().contains("INVALID_PARAMETER"));

        HttpRequest req2 = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/-1"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp2 = http.send(req2, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp2.statusCode());
        assertTrue(resp2.body().contains("INVALID_PARAMETER"));
    }

    @Test
    void testPostIngestUpstreamError() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/5"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(502, resp.statusCode());
        assertTrue(resp.body().contains("UPSTREAM_ERROR"));

        // Status for failed book
        HttpRequest reqStatus = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/status/5")).GET().build();
        HttpResponse<String> respStatus = http.send(reqStatus, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respStatus.statusCode());
        assertTrue(respStatus.body().contains("\"status\":\"failed\""));
    }

    @Test
    void testGetStatusNotFound() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUri + "/ingest/status/9999")).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
        assertTrue(resp.body().contains("NOT_FOUND"));
    }

    @Test
    void testPostIngestBrokerUnavailableReturns503AndSavesBook() throws Exception {
        ActiveMQConnectionFactory brokenBroker = new ActiveMQConnectionFactory("tcp://localhost:60001?timeout=500");
        BookDownloadedPublisher brokenPublisher = new BookDownloadedPublisher(brokenBroker);
        GutenbergClient client = new GutenbergClient("http://localhost:" + fakeGutenberg.port(), Duration.ofSeconds(2));
        DatalakeWriter writer = new DatalakeWriter(tempDir.resolve("datalake_broken"), DatalakeLayout.TIME_BASED);
        BookDownloader downloader = new BookDownloader(client, writer, Clock.fixed(NOW, ZoneOffset.UTC));
        IngestionLogStore store = new IngestionLogStore(tempDir.resolve("datalake_broken/ingestion.db"));
        IngestionService brokenService = new IngestionService(downloader, store, brokenPublisher);

        Javalin appWithBrokenBroker = IngestionApp.start(0, brokenService);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + appWithBrokenBroker.port() + "/ingest/1342"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(503, resp.statusCode());
            assertTrue(resp.body().contains("UNAVAILABLE"));
            assertTrue(resp.body().contains("Message broker is unavailable"));

            // Book must be saved as available in store
            HttpRequest statusReq = HttpRequest.newBuilder(URI.create("http://localhost:" + appWithBrokenBroker.port() + "/ingest/status/1342"))
                    .GET()
                    .build();
            HttpResponse<String> statusResp = http.send(statusReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, statusResp.statusCode());
            assertTrue(statusResp.body().contains("\"status\":\"available\""));
        } finally {
            appWithBrokenBroker.stop();
            brokenPublisher.close();
        }
    }
}
