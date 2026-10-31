package com.thescratchers.searchengine.benchmarks.load;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Runs one concurrency level as a closed loop: each client is a thread with its own HTTP
 * connection that sends a request, waits for the whole response and immediately sends the next
 * one, with no think time. Client {@code c} starts at query {@code c} of the list and at replica
 * {@code c}, and then moves through both lists in order, so the workload is deterministic and
 * every replica receives the same share of requests.
 *
 * <p>Requests started during the warm-up are not recorded. Recording stops at the end of the
 * measurement; requests still in flight are completed but discarded.
 */
public final class LoadRunner {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final LoadOptions options;

    public LoadRunner(LoadOptions options) {
        this.options = options;
    }

    /** Runs {@code clients} concurrent clients and returns the samples of the measurement phase. */
    public List<Sample> run(int clients) throws InterruptedException {
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch go = new CountDownLatch(1);
        long[] window = new long[2];
        List<Thread> threads = new ArrayList<>();
        for (int client = 0; client < clients; client++) {
            int id = client;
            Thread thread = new Thread(() -> runClient(clients, id, ready, go, window, samples), "load-client-" + id);
            threads.add(thread);
            thread.start();
        }
        ready.await();
        long now = System.nanoTime();
        window[0] = now + options.warmup().toNanos();
        window[1] = window[0] + options.duration().toNanos();
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        synchronized (samples) {
            return List.copyOf(samples);
        }
    }

    private void runClient(int clients, int client, CountDownLatch ready, CountDownLatch go, long[] window,
                           List<Sample> samples) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        List<String> queries = options.queries();
        List<String> targets = options.targets();
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long measureStart = window[0];
        long measureEnd = window[1];
        for (long i = 0; ; i++) {
            long start = System.nanoTime();
            if (start >= measureEnd) {
                return;
            }
            String query = queries.get((int) ((client + i) % queries.size()));
            String target = targets.get((int) ((client + i) % targets.size()));
            int status;
            int total = -1;
            try {
                HttpResponse<String> response = http.send(request(target, query), HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                if (status == 200) {
                    total = totalOf(response.body());
                }
            } catch (IOException e) {
                status = -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long end = System.nanoTime();
            if (start >= measureStart && end <= measureEnd) {
                samples.add(new Sample(clients, client, target, query, status, (start - measureStart) / 1000,
                        (end - start) / 1000, total));
            }
        }
    }

    private HttpRequest request(String target, String query) {
        String path = options.pathTemplate().replace("{q}", URLEncoder.encode(query, StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(target + path)).timeout(REQUEST_TIMEOUT).GET().build();
    }

    /** Reads the {@code total} field of a JSON body, or returns {@code -1} if there is none. */
    static int totalOf(String body) {
        try {
            JsonElement json = JsonParser.parseString(body);
            if (json.isJsonObject()) {
                JsonObject object = json.getAsJsonObject();
                if (object.has("total") && object.get("total").isJsonPrimitive()) {
                    return object.get("total").getAsInt();
                }
            }
        } catch (JsonParseException | NumberFormatException e) {
            return -1;
        }
        return -1;
    }
}
