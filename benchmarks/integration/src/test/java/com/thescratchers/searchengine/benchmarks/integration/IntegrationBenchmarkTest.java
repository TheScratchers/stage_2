package com.thescratchers.searchengine.benchmarks.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntegrationBenchmarkTest {

    @Test
    void parsesTheMemoryColumnOfTasklistInAnyLocale() {
        String output = "\"java.exe\",\"1234\",\"Console\",\"1\",\"123.456 KB\"\r\n"
                + "\"java.exe\",\"77\",\"Console\",\"1\",\"9,876 K\"\r\n"
                + "INFO: No tasks are running which match the specified criteria.\r\n";

        assertEquals(Map.of(1234L, 123456L, 77L, 9876L), ResourceSampler.parseTasklist(output));
    }

    @Test
    void readsTheResidentMemoryOfThisJvm() {
        Map<Long, Long> rss = ResourceSampler.residentMemoryKb();

        assertFalse(rss.isEmpty());
        assertEquals(true, rss.getOrDefault(ProcessHandle.current().pid(), 0L) > 1024, rss.toString());
    }

    @Test
    void summarisesWithNearestRankPercentiles() {
        double[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        assertEquals(5.0, IntegrationBenchmark.percentile(sorted, 50));
        assertEquals(10.0, IntegrationBenchmark.percentile(sorted, 95));
        assertEquals("x,5.00,10.00,10.00", IntegrationBenchmark.summaryRow("x", sorted));
        assertEquals("x,0.00,0.00,0.00", IntegrationBenchmark.summaryRow("x", new double[0]));
    }

    @Test
    void quickModeKeepsEveryExperimentButShortensThem() {
        IntegrationBenchmark.Settings quick = IntegrationBenchmark.Settings.parse("--quick", "--heap", "512m");
        IntegrationBenchmark.Settings full = IntegrationBenchmark.Settings.parse();

        assertEquals(List.of("latency", "indexing", "coordination"), quick.experiments());
        assertEquals("512m", quick.heap());
        assertEquals(List.of(1, 2, 4, 8, 16, 32, 64), full.clientLevels());
        assertEquals(Duration.ofSeconds(30), full.duration());
        assertEquals(3, full.repeats());
        assertEquals("1g", full.heap());
    }
}
