package com.thescratchers.searchengine.apitests.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Starts the whole system as on the Stage 2 host (broker, Ingestion, Indexing and Search, each one
 * from its executable jar in its own JVM), loads the fixed corpus through the public Ingestion API
 * and waits until the choreography has indexed every book and Search has seen it.
 *
 * <p>Every run uses a fresh {@code DATA_DIR} and free ports, so it never touches an existing
 * deployment. The processes' output goes to {@code DATA_DIR/logs/}.
 */
public final class Pipeline implements AutoCloseable {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration INDEXING_TIMEOUT = Duration.ofMinutes(3);

    private final Path repo;
    private final Path dataDir;
    private final String gutenbergUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Process> processes = new ArrayList<>();

    private int brokerPort;
    private int ingestionPort;
    private int indexingPort;
    private int searchPort;

    /**
     * @param repo         repository root, where {@code <module>/target/<module>.jar} are found
     * @param dataDir      empty directory used as {@code DATA_DIR}
     * @param gutenbergUrl {@code GUTENBERG_BASE_URL} for the Ingestion Service
     */
    public Pipeline(Path repo, Path dataDir, String gutenbergUrl) {
        this.repo = repo;
        this.dataDir = dataDir;
        this.gutenbergUrl = gutenbergUrl;
    }

    public String ingestionUrl() {
        return "http://localhost:" + ingestionPort;
    }

    public String indexingUrl() {
        return "http://localhost:" + indexingPort;
    }

    public String searchUrl() {
        return "http://localhost:" + searchPort;
    }

    /** Starts the four processes and waits until each one accepts requests. */
    public void start() throws IOException, InterruptedException {
        Files.createDirectories(dataDir.resolve("logs"));
        brokerPort = freePort();
        ingestionPort = freePort();
        indexingPort = freePort();
        searchPort = freePort();

        launch("broker", Map.of());
        await("the broker on port " + brokerPort, STARTUP_TIMEOUT, () -> portOpen(brokerPort));
        launch("indexing-service", Map.of("INDEXING_PORT", String.valueOf(indexingPort)));
        launch("search-service", Map.of("SEARCH_PORT", String.valueOf(searchPort)));
        launch("ingestion-service", Map.of("INGESTION_PORT", String.valueOf(ingestionPort),
                "GUTENBERG_BASE_URL", gutenbergUrl));
        for (String url : List.of(indexingUrl(), searchUrl(), ingestionUrl())) {
            await(url + "/health", STARTUP_TIMEOUT, () -> status("GET", url + "/health") == 200);
        }
    }

    /**
     * Ingests every book with {@code POST /ingest/{id}} and waits until the Indexing Service reports
     * all of them indexed and Search lists all of them in {@code GET /search/filters}.
     *
     * @throws IllegalStateException if a book cannot be ingested
     */
    public void loadCorpus(List<Integer> bookIds) throws IOException, InterruptedException {
        for (int bookId : bookIds) {
            int status = status("POST", ingestionUrl() + "/ingest/" + bookId);
            if (status != 200) {
                throw new IllegalStateException("POST /ingest/" + bookId + " answered " + status);
            }
        }
        int expected = bookIds.size();
        await(expected + " books indexed", INDEXING_TIMEOUT,
                () -> intField(indexingUrl() + "/index/status", "books_indexed") == expected);
        await(expected + " books visible to Search", INDEXING_TIMEOUT,
                () -> intField(searchUrl() + "/search/filters", "total_books") == expected);
    }

    /** Stops every process started by this pipeline. */
    @Override
    public void close() {
        for (int i = processes.size() - 1; i >= 0; i--) {
            Process process = processes.get(i);
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void launch(String module, Map<String, String> extraEnv) throws IOException {
        Path jar = repo.resolve(module).resolve("target").resolve(module + ".jar");
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Missing " + jar + "; run mvn clean verify first");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(dataDir.resolve("logs").resolve(module + ".log").toFile());
        builder.environment().put("DATA_DIR", dataDir.toAbsolutePath().toString());
        builder.environment().put("BROKER_URL", "tcp://localhost:" + brokerPort);
        builder.environment().putAll(extraEnv);
        processes.add(builder.start());
    }

    private int status(String method, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private int intField(String url, String field) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return -1;
            }
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            return body.has(field) ? body.get(field).getAsInt() : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            for (Process process : processes) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("A service exited while waiting for " + what
                            + "; see " + dataDir.resolve("logs"));
                }
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Timed out waiting for " + what + "; see " + dataDir.resolve("logs"));
            }
            Thread.sleep(250);
        }
    }

    private static boolean portOpen(int port) {
        try (Socket ignored = new Socket("localhost", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
