package com.thescratchers.searchengine.ingestion;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionAppHealthTest {

    private Javalin app;

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        app = IngestionApp.start(0);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.body().contains("\"service\":\"ingestion-service\""));
        assertTrue(response.body().contains("\"status\":\"UP\""));
    }
}
