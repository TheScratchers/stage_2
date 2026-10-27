package com.thescratchers.searchengine.search.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thescratchers.searchengine.search.SearchApp;
import com.thescratchers.searchengine.search.cache.FilterValuesCache;
import com.thescratchers.searchengine.search.store.DatamartReader;
import com.thescratchers.searchengine.search.store.TestDatamart;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Search API over HTTP against the hand-built datamart of {@link TestDatamart};
 * expected bodies follow {@code docs/openapi.yaml}.
 */
class SearchControllerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private Javalin app;

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void searchReturnsTheDocumentedBody() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        HttpResponse<String> response = get("/search?q=raft");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(json("{\"query\":\"raft\",\"terms\":[\"raft\"],\"filters\":{},\"total\":1,\"offset\":0,"
                + "\"limit\":20,\"results\":[{\"book_id\":1,\"title\":\"Life on the River\",\"author\":\"Mark Twain\","
                + "\"language\":\"en\",\"year\":2004,\"score\":3}]}"), json(response.body()));
    }

    @Test
    void searchReportsNormalisedFiltersAndThePage() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        JsonObject body = json(get("/search?q=adventure&author=twain&language=EN&year=2004&offset=1&limit=1").body());

        assertEquals(json("{\"author\":\"twain\",\"language\":\"en\",\"year\":2004}"), body.getAsJsonObject("filters"));
        assertEquals(2, body.get("total").getAsInt());
        assertEquals(1, body.get("offset").getAsInt());
        assertEquals(1, body.get("limit").getAsInt());
        assertEquals(1, body.getAsJsonArray("results").size());
        assertEquals(1, body.getAsJsonArray("results").get(0).getAsJsonObject().get("book_id").getAsInt());
    }

    @Test
    void missingMetadataIsSerialisedAsNull() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        JsonObject hit = json(get("/search?q=rivi%C3%A8re").body()).getAsJsonArray("results").get(0).getAsJsonObject();

        assertTrue(hit.has("author"));
        assertTrue(hit.get("author").isJsonNull());
    }

    @Test
    void validQueryWithoutMatchesReturnsAnEmptyList() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        HttpResponse<String> response = get("/search?q=zxqv");

        assertEquals(200, response.statusCode());
        JsonObject body = json(response.body());
        assertEquals(0, body.get("total").getAsInt());
        assertEquals(0, body.getAsJsonArray("results").size());
    }

    @Test
    void invalidParametersReturn400WithTheErrorEnvelope() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        assertError(get("/search"), 400, "INVALID_PARAMETER", "q is required");
        assertError(get("/search?q=%3F%3F"), 400, "INVALID_PARAMETER", "q must contain at least one word");
        assertError(get("/search?q=time&limit=500"), 400, "INVALID_PARAMETER", "limit must be between 1 and 100");
        assertError(get("/search?q=time&year=98"), 400, "INVALID_PARAMETER", "year must be a four-digit number");
        assertError(get("/books/abc"), 400, "INVALID_PARAMETER", "book_id must be a positive integer");
        assertError(get("/books/0"), 400, "INVALID_PARAMETER", "book_id must be a positive integer");
    }

    @Test
    void bookReturnsItsMetadataOr404() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        HttpResponse<String> found = get("/books/3");
        assertEquals(200, found.statusCode());
        assertEquals(json("{\"book_id\":3,\"title\":\"Moorland Stories\",\"author\":\"Charlotte Brontë\","
                + "\"language\":\"en\",\"year\":1998,\"indexed_at\":\"2026-10-25T10:00:03Z\"}"), json(found.body()));

        assertError(get("/books/999"), 404, "NOT_FOUND", "book 999 is not indexed");
    }

    @Test
    void filtersListLanguagesAndYears() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        HttpResponse<String> response = get("/search/filters");

        assertEquals(200, response.statusCode());
        assertEquals(json("{\"total_books\":6,\"languages\":[{\"code\":\"en\",\"books\":3},{\"code\":\"fr\",\"books\":2}],"
                + "\"years\":[{\"year\":1998,\"books\":2},{\"year\":2004,\"books\":2},{\"year\":2010,\"books\":1}],"
                + "\"index_updated_at\":\"" + TestDatamart.LAST_UPDATE + "\"}"), json(response.body()));
    }

    @Test
    void missingIndexReturns503ButHealthIsUp() throws Exception {
        startWith(tempDir.resolve("data").resolve("datamarts").resolve("datamart.db"));

        assertError(get("/search?q=time"), 503, "UNAVAILABLE", "the index is not available yet");
        assertError(get("/books/1"), 503, "UNAVAILABLE", "the index is not available yet");
        assertError(get("/search/filters"), 503, "UNAVAILABLE", "the index is not available yet");
        assertEquals(200, get("/health").statusCode());
    }

    @Test
    void unexpectedErrorsReturn500WithoutInternalDetails() throws Exception {
        Path notADatabase = Files.createDirectory(tempDir.resolve("directory.db"));
        startWith(notADatabase);

        assertError(get("/search?q=time"), 500, "INTERNAL_ERROR", "unexpected error");
    }

    @Test
    void browsersFromAnyOriginCanCallTheApi() throws Exception {
        startWith(TestDatamart.create(tempDir.resolve("datamart.db")));

        HttpResponse<String> simple = send(HttpRequest.newBuilder(uri("/search?q=raft"))
                .header("Origin", "http://frontend.example").GET().build());
        assertEquals("*", simple.headers().firstValue("Access-Control-Allow-Origin").orElse(""));

        HttpResponse<String> preflight = send(HttpRequest.newBuilder(uri("/search?q=raft"))
                .header("Origin", "http://frontend.example")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build());
        assertTrue(preflight.statusCode() < 300);
        assertEquals("*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertFalse(preflight.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
    }

    private void startWith(Path datamart) {
        DatamartReader reader = new DatamartReader(datamart);
        app = SearchApp.start(0, reader, new FilterValuesCache(reader));
    }

    private URI uri(String pathAndQuery) {
        return URI.create("http://localhost:" + app.port() + pathAndQuery);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return send(HttpRequest.newBuilder(uri(pathAndQuery)).GET().build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(json("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}"),
                json(response.body()));
    }
}
