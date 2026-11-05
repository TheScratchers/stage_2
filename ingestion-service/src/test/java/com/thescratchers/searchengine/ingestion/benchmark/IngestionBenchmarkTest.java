package com.thescratchers.searchengine.ingestion.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionBenchmarkTest {

    @TempDir
    Path tempDir;

    @Test
    void testQuickIngestionBenchmarkExecution() throws Exception {
        Path outDir = tempDir.resolve("results");
        IngestionBenchmark.Settings settings = new IngestionBenchmark.Settings(
                List.of(1, 2),
                1,
                outDir,
                Path.of("..", "samples", "books.txt"),
                Path.of("..", "samples", "books"),
                true
        );

        IngestionBenchmark benchmark = new IngestionBenchmark(settings);
        benchmark.run();

        // Verify that the official results directory and mandatory files were generated
        try (var stream = Files.list(outDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).toList();
            assertFalse(dirs.isEmpty(), "Should create at least one results directory");

            Path resultDir = dirs.get(0);
            assertTrue(Files.isRegularFile(resultDir.resolve("raw.csv")), "raw.csv must be present");
            assertTrue(Files.isRegularFile(resultDir.resolve("summary.csv")), "summary.csv must be present");
            assertTrue(Files.isRegularFile(resultDir.resolve("command.txt")), "command.txt must be present");
            assertTrue(Files.isRegularFile(resultDir.resolve("host.txt")), "host.txt must be present");
            assertTrue(Files.isRegularFile(resultDir.resolve("resources.csv")), "resources.csv must be present");
            assertTrue(Files.isRegularFile(resultDir.resolve("resources-summary.csv")), "resources-summary.csv must be present");
            assertTrue(Files.isRegularFile(resultDir.resolve("disk.csv")), "disk.csv must be present");

            // Verify content non-empty
            assertTrue(Files.size(resultDir.resolve("raw.csv")) > 50, "raw.csv should contain rows");
            assertTrue(Files.size(resultDir.resolve("summary.csv")) > 50, "summary.csv should contain rows");
        }
    }
}
