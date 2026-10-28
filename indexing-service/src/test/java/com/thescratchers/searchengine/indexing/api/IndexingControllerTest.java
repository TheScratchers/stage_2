package com.thescratchers.searchengine.indexing.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.IndexingApp;
import com.thescratchers.searchengine.indexing.IngestionLogFixture;
import com.thescratchers.searchengine.indexing.service.IndexListener;
import com.thescratchers.searchengine.indexing.service.IndexingService;
import com.thescratchers.searchengine.indexing.source.IngestionLogReader;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingControllerTest {

    @TempDir
    Path tempDir;

    private IngestionLogFixture log;
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Path ingestionDb = tempDir.resolve("ingestion.db");
        log = new IngestionLogFixture(ingestionDb);
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));
        IndexingService service = new IndexingService(new IngestionLogReader(ingestionDb), new BookIndexer(store), store, IndexListener.NONE);
        app = IndexingApp.start(0, service);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    @Test
    void healthReportsUp() throws Exception {
        HttpResponse<String> response = send("GET", "/health");

        assertEquals(200, response.statusCode());
        assertEquals("indexing-service", json(response).get("service").getAsString());
        assertEquals("UP", json(response).get("status").getAsString());
    }

    @Test
    void updateIndexesAnAvailableBook() throws Exception {
        log.available(1342);

        HttpResponse<String> response = send("POST", "/index/update/1342");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonObject body = json(response);
        assertEquals(1342, body.get("book_id").getAsInt());
        assertEquals("updated", body.get("index").getAsString());
        assertTrue(body.get("distinct_terms").getAsInt() > 0);
        assertTrue(body.get("total_tokens").getAsInt() > body.get("distinct_terms").getAsInt());
    }

    @Test
    void updateRejectsAnInvalidBookId() throws Exception {
        for (String id : new String[] {"abc", "0", "-3", "1.5"}) {
            HttpResponse<String> response = send("POST", "/index/update/" + id);

            assertEquals(400, response.statusCode(), id);
            assertEquals("INVALID_PARAMETER", error(response).get("code").getAsString());
        }
    }

    @Test
    void updateOfABookThatIsNotAvailableIsNotFound() throws Exception {
        HttpResponse<String> response = send("POST", "/index/update/999");

        assertEquals(404, response.statusCode());
        assertEquals("NOT_FOUND", error(response).get("code").getAsString());
    }

    @Test
    void rebuildReportsWhatItDid() throws Exception {
        log.available(1342).available(11);

        HttpResponse<String> response = send("POST", "/index/rebuild");

        assertEquals(200, response.statusCode());
        JsonObject body = json(response);
        assertEquals(2, body.get("books_processed").getAsInt());
        assertEquals(0, body.get("books_removed").getAsInt());
        assertEquals(0, body.getAsJsonArray("failed_books").size());
        assertTrue(body.get("elapsed_time").getAsString().endsWith("s"));
    }

    @Test
    void statusReflectsTheIndex() throws Exception {
        JsonObject empty = json(send("GET", "/index/status"));
        assertEquals(0, empty.get("books_indexed").getAsInt());
        assertTrue(empty.get("last_update").isJsonNull());

        log.available(1342);
        send("POST", "/index/update/1342");
        JsonObject status = json(send("GET", "/index/status"));

        assertEquals(1, status.get("books_indexed").getAsInt());
        assertTrue(status.get("last_update").getAsString().endsWith("Z"));
        assertTrue(status.get("index_size_mb").getAsDouble() >= 0);
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static JsonObject error(HttpResponse<String> response) {
        return json(response).getAsJsonObject("error");
    }
}
