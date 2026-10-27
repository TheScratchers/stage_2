package com.thescratchers.searchengine.benchmarks.load;

import java.util.Arrays;
import java.util.List;

/**
 * Aggregated results of one concurrency level. Latency percentiles use the nearest-rank method over
 * successful requests only; failed requests are counted in {@code errors}.
 *
 * @param clients       concurrency level
 * @param requests      requests completed during the measurement
 * @param errors        requests without a {@code 200} response
 * @param throughputRps successful requests per second of measurement
 * @param meanMs        mean latency of successful requests, in milliseconds
 * @param p50Ms         median latency
 * @param p95Ms         95th percentile latency
 * @param p99Ms         99th percentile latency
 * @param maxMs         maximum latency
 */
public record LevelSummary(int clients, int requests, int errors, double throughputRps, double meanMs,
                           double p50Ms, double p95Ms, double p99Ms, double maxMs) {

    /** Summarises the samples of one level measured for {@code seconds} seconds. */
    public static LevelSummary of(int clients, List<Sample> samples, double seconds) {
        long[] latencies = samples.stream().filter(Sample::ok).mapToLong(Sample::latencyMicros).sorted().toArray();
        int errors = samples.size() - latencies.length;
        double mean = latencies.length == 0 ? 0 : Arrays.stream(latencies).average().orElse(0) / 1000.0;
        return new LevelSummary(clients, samples.size(), errors, latencies.length / seconds, mean,
                percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99),
                latencies.length == 0 ? 0 : latencies[latencies.length - 1] / 1000.0);
    }

    /** Nearest-rank percentile of sorted latencies in microseconds, returned in milliseconds. */
    static double percentile(long[] sortedMicros, double percentile) {
        if (sortedMicros.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sortedMicros.length);
        return sortedMicros[Math.max(rank, 1) - 1] / 1000.0;
    }
}
