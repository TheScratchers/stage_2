package com.thescratchers.searchengine.ingestion.gutenberg;

import com.thescratchers.searchengine.common.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Downloads the plain-text edition of a book from Project Gutenberg.
 *
 * <p>A single {@link HttpClient} is shared by every call (it is thread-safe and keeps connections
 * alive), and both the connection and each request have a timeout, so a slow or silent server can
 * no longer block a request thread forever as it could in Stage 1.
 */
public final class GutenbergClient {

    /** Setting with the Gutenberg base URL (default {@value #DEFAULT_BASE_URL}). */
    public static final String BASE_URL_KEY = "GUTENBERG_BASE_URL";
    /** Setting with the per-request timeout in seconds (default {@value #DEFAULT_TIMEOUT_SECONDS}). */
    public static final String TIMEOUT_KEY = "GUTENBERG_TIMEOUT_SECONDS";

    private static final String DEFAULT_BASE_URL = "https://www.gutenberg.org";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;

    public GutenbergClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    /** Client configured from {@code GUTENBERG_BASE_URL} and {@code GUTENBERG_TIMEOUT_SECONDS}. */
    public static GutenbergClient fromConfig() {
        return new GutenbergClient(Config.get(BASE_URL_KEY, DEFAULT_BASE_URL),
                Duration.ofSeconds(Config.getInt(TIMEOUT_KEY, DEFAULT_TIMEOUT_SECONDS)));
    }

    /** URL of the plain-text edition of {@code bookId}. */
    public URI urlOf(int bookId) {
        return URI.create(baseUrl + "/cache/epub/" + bookId + "/pg" + bookId + ".txt");
    }

    /**
     * Returns the full raw text of {@code bookId}.
     *
     * @throws DownloadException {@code NOT_FOUND} on HTTP 404, {@code UPSTREAM_ERROR} on any other
     *                           status, timeout or network failure
     */
    public String fetch(int bookId) throws DownloadException {
        HttpRequest request = HttpRequest.newBuilder(urlOf(bookId)).timeout(timeout).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new DownloadException(bookId, DownloadException.Reason.UPSTREAM_ERROR,
                    "Gutenberg did not answer within " + timeout.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new DownloadException(bookId, DownloadException.Reason.UPSTREAM_ERROR,
                    "cannot reach Gutenberg: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException(bookId, DownloadException.Reason.UPSTREAM_ERROR, "download interrupted", e);
        }
        if (response.statusCode() == 404) {
            throw new DownloadException(bookId, DownloadException.Reason.NOT_FOUND,
                    "not found on Gutenberg (" + request.uri() + ")");
        }
        if (response.statusCode() != 200) {
            throw new DownloadException(bookId, DownloadException.Reason.UPSTREAM_ERROR,
                    "Gutenberg answered HTTP " + response.statusCode());
        }
        return response.body();
    }
}
