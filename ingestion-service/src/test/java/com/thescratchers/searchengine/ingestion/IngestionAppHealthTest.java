package com.thescratchers.searchengine.ingestion;

import com.thescratchers.searchengine.ingestion.broker.BookDownloadedPublisher;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeLayout;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeWriter;
import com.thescratchers.searchengine.ingestion.datalake.IngestionLogStore;
import com.thescratchers.searchengine.ingestion.gutenberg.GutenbergClient;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionAppHealthTest {

    @TempDir
    Path tempDir;

    private Javalin app;
    private BookDownloadedPublisher publisher;

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        GutenbergClient client = new GutenbergClient("http://localhost:1", Duration.ofSeconds(1));
        DatalakeWriter writer = new DatalakeWriter(tempDir.resolve("datalake"), DatalakeLayout.TIME_BASED);
        BookDownloader downloader = new BookDownloader(client, writer, Clock.systemUTC());
        IngestionLogStore store = new IngestionLogStore(tempDir.resolve("datalake/ingestion.db"));

        ActiveMQConnectionFactory brokerFactory = new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
        publisher = new BookDownloadedPublisher(brokerFactory);
        IngestionService service = new IngestionService(downloader, store, publisher);

        app = IngestionApp.start(0, service);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.body().contains("\"service\":\"ingestion-service\""));
        assertTrue(response.body().contains("\"status\":\"UP\""));
    }
}

