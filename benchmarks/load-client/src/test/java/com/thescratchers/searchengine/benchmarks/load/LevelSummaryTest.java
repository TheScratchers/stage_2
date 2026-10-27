package com.thescratchers.searchengine.benchmarks.load;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelSummaryTest {

    @Test
    void usesNearestRankPercentiles() {
        long[] micros = LongStream.rangeClosed(1, 100).map(i -> i * 1000).toArray();

        assertEquals(50.0, LevelSummary.percentile(micros, 50));
        assertEquals(95.0, LevelSummary.percentile(micros, 95));
        assertEquals(99.0, LevelSummary.percentile(micros, 99));
        assertEquals(1.0, LevelSummary.percentile(micros, 0));
        assertEquals(7.0, LevelSummary.percentile(new long[] {7000}, 99));
        assertEquals(0.0, LevelSummary.percentile(new long[0], 50));
    }

    @Test
    void summarisesSuccessfulRequestsAndCountsErrorsApart() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            samples.add(sample(200, i * 1000));
        }
        samples.add(sample(500, 999_000));
        samples.add(sample(-1, 999_000));

        LevelSummary summary = LevelSummary.of(8, samples, 2.0);

        assertEquals(new LevelSummary(8, 6, 2, 2.0, 2.5, 2.0, 4.0, 4.0, 4.0), summary);
    }

    @Test
    void emptyLevelHasZeroes() {
        assertEquals(new LevelSummary(1, 0, 0, 0, 0, 0, 0, 0, 0), LevelSummary.of(1, List.of(), 1.0));
    }

    private static Sample sample(int status, long latencyMicros) {
        return new Sample(8, 0, "http://localhost", "time", status, 0, latencyMicros, status == 200 ? 3 : -1);
    }
}
