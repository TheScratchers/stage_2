package com.thescratchers.searchengine.benchmarks.load;

/**
 * One measured request.
 *
 * @param clients       concurrency level the request belongs to
 * @param client        index of the client (thread) that sent it
 * @param target        base URL of the replica that answered
 * @param query         query word
 * @param status        HTTP status, or {@code -1} if no response was received
 * @param startMicros   start time, in microseconds since the beginning of the measurement
 * @param latencyMicros time from sending the request to receiving the whole response
 * @param total         {@code total} field of a {@code 200} search response, or {@code -1}; it lets
 *                      a run check that every replica returned the same number of matches
 */
public record Sample(int clients, int client, String target, String query, int status, long startMicros,
                     long latencyMicros, int total) {

    /** Whether the request succeeded with {@code 200 OK}. */
    public boolean ok() {
        return status == 200;
    }
}
