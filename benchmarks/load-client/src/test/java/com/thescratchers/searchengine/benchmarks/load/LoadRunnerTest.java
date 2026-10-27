package com.thescratchers.searchengine.benchmarks.load;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the load loop against two stub replicas built with the JDK HTTP server. */
class LoadRunnerTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void spreadsRequestsOverReplicasAndRecordsStatusAndTotal() throws Exception {
        String first = startStub();
        String second = startStub();
        LoadOptions options = new LoadOptions(List.of(first, second), List.of(4), Duration.ZERO, Duration.ofSeconds(1),
                "/search?q={q}", Path.of("words.txt"), List.of("adventure", "bad word"), Path.of("unused"));

        List<Sample> samples = new LoadRunner(options).run(4);

        assertTrue(samples.size() > 20, "expected a steady stream of requests, got " + samples.size());
        Map<String, Long> perTarget = samples.stream().collect(Collectors.groupingBy(Sample::target, Collectors.counting()));
        assertEquals(2, perTarget.size());
        assertTrue(Math.abs(perTarget.get(first) - perTarget.get(second)) <= 4, perTarget.toString());

        for (Sample sample : samples) {
            assertEquals(4, sample.clients());
            assertTrue(sample.latencyMicros() > 0);
            assertTrue(sample.startMicros() >= 0 && sample.startMicros() < 1_000_000);
            if (sample.query().equals("adventure")) {
                assertEquals(200, sample.status());
                assertEquals(5, sample.total());
            } else {
                assertEquals(500, sample.status());
                assertEquals(-1, sample.total());
            }
        }
    }

    @Test
    void unreachableReplicasAreRecordedAsFailedRequests() throws Exception {
        LoadOptions options = new LoadOptions(List.of("http://127.0.0.1:1"), List.of(1), Duration.ZERO,
                Duration.ofSeconds(1), "/search?q={q}", Path.of("words.txt"), List.of("time"), Path.of("unused"));

        List<Sample> samples = new LoadRunner(options).run(1);

        assertTrue(samples.stream().allMatch(sample -> sample.status() == -1));
    }

    @Test
    void readsTheTotalOfASearchResponse() {
        assertEquals(14, LoadRunner.totalOf("{\"query\":\"monster\",\"total\":14,\"results\":[]}"));
        assertEquals(-1, LoadRunner.totalOf("{\"error\":{}}"));
        assertEquals(-1, LoadRunner.totalOf("not json"));
    }

    /** Stub replica: 200 with {@code total} 5 for "adventure", 500 for anything else. */
    private String startStub() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            boolean known = exchange.getRequestURI().getRawQuery().equals("q=adventure");
            byte[] body = (known ? "{\"total\":5,\"results\":[]}" : "{\"error\":{}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(known ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
