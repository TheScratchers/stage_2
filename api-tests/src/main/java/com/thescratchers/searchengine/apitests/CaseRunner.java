package com.thescratchers.searchengine.apitests;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the HTTP checks of a case file against a running service.
 *
 * <p>A case file is a JSON object with a {@code description} and a list of {@code cases}. Each case
 * has a {@code name}, an optional {@code method} (default {@code GET}), a {@code path} with its
 * query string, optional request {@code headers}, the expected {@code status}, an optional
 * expected {@code body} fragment (compared with {@link JsonSubset}) and optional expected
 * {@code response_headers}.
 */
public final class CaseRunner {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;

    /** @param baseUrl service URL without a trailing slash, e.g. {@code http://localhost:7003} */
    public CaseRunner(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Outcome of one case: {@code failure} is empty when the case passed. */
    public record Result(String name, Optional<String> failure) {

        public boolean passed() {
            return failure.isEmpty();
        }
    }

    /** Runs every case of {@code caseFile} in order. */
    public List<Result> run(Path caseFile) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(caseFile, StandardCharsets.UTF_8)).getAsJsonObject();
        List<Result> results = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            String name = testCase.get("name").getAsString();
            results.add(new Result(name, check(testCase)));
        }
        return results;
    }

    private Optional<String> check(JsonObject testCase) {
        HttpResponse<String> response;
        try {
            response = http.send(request(testCase), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.of("request failed: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        }

        int expectedStatus = testCase.get("status").getAsInt();
        if (response.statusCode() != expectedStatus) {
            return Optional.of("expected status " + expectedStatus + " but was " + response.statusCode()
                    + ": " + response.body());
        }
        if (testCase.has("response_headers")) {
            for (Map.Entry<String, JsonElement> header : testCase.getAsJsonObject("response_headers").entrySet()) {
                String expected = header.getValue().getAsString();
                String actual = response.headers().firstValue(header.getKey()).orElse(null);
                if (!expected.equals(actual)) {
                    return Optional.of("header " + header.getKey() + ": expected " + expected + " but was " + actual);
                }
            }
        }
        if (testCase.has("body")) {
            JsonElement actual;
            try {
                actual = JsonParser.parseString(response.body());
            } catch (JsonParseException e) {
                return Optional.of("body is not JSON: " + response.body());
            }
            return JsonSubset.mismatch(testCase.get("body"), actual);
        }
        return Optional.empty();
    }

    private HttpRequest request(JsonObject testCase) {
        String method = testCase.has("method") ? testCase.get("method").getAsString() : "GET";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + testCase.get("path").getAsString()))
                .timeout(TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (testCase.has("headers")) {
            for (Map.Entry<String, JsonElement> header : testCase.getAsJsonObject("headers").entrySet()) {
                builder.header(header.getKey(), header.getValue().getAsString());
            }
        }
        return builder.build();
    }
}
