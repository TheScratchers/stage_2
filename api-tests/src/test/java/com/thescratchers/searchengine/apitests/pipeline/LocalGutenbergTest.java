package com.thescratchers.searchengine.apitests.pipeline;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGutenbergTest {

    private static final Path BOOKS = Path.of("..", "samples", "books");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void servesABundledBookWithItsMarkersAsGutenbergDoes() throws Exception {
        try (LocalGutenberg gutenberg = new LocalGutenberg(BOOKS)) {
            HttpResponse<String> response = get(gutenberg.baseUrl() + "/cache/epub/1342/pg1342.txt");

            assertEquals(200, response.statusCode());
            String text = response.body();
            String header = Files.readString(BOOKS.resolve("1342.header.txt"), StandardCharsets.UTF_8);
            String body = Files.readString(BOOKS.resolve("1342.body.txt"), StandardCharsets.UTF_8);
            assertTrue(text.startsWith(header));
            assertTrue(text.contains("*** START OF THE PROJECT GUTENBERG EBOOK 1342 ***"));
            assertTrue(text.contains(body));
            assertTrue(text.indexOf("*** END OF THE PROJECT GUTENBERG EBOOK 1342 ***") > text.indexOf(body));
        }
    }

    @Test
    void unknownBooksAndPathsAre404() throws Exception {
        try (LocalGutenberg gutenberg = new LocalGutenberg(BOOKS)) {
            assertEquals(404, get(gutenberg.baseUrl() + "/cache/epub/99999999/pg99999999.txt").statusCode());
            assertEquals(404, get(gutenberg.baseUrl() + "/cache/epub/1342/pg11.txt").statusCode());
            assertEquals(404, get(gutenberg.baseUrl() + "/files/1342").statusCode());
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
