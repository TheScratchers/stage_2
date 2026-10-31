package com.thescratchers.searchengine.benchmarks.load;

import com.thescratchers.searchengine.apitests.FixtureDatamart;
import com.thescratchers.searchengine.search.SearchApp;
import com.thescratchers.searchengine.search.cache.FilterValuesCache;
import com.thescratchers.searchengine.search.store.DatamartReader;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole load client, as from the command line, against a real Search Service over the
 * fixture datamart of the API checks, and inspects the results directory it writes.
 */
class LoadClientSmokeTest {

    @TempDir
    Path tempDir;

    private Javalin app;

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void writesRawResultsSummaryCommandAndHost() throws Exception {
        DatamartReader reader = new DatamartReader(FixtureDatamart.create(tempDir.resolve("data")));
        app = SearchApp.start(0, reader, new FilterValuesCache(reader));
        Path words = Files.write(tempDir.resolve("words.txt"), List.of("# fixture words", "adventure", "river", "moor"));
        Path out = tempDir.resolve("results");

        LoadClient.main(new String[] {"--targets", "http://localhost:" + app.port(), "--clients", "1,2",
                "--warmup", "0", "--duration", "1", "--queries", words.toString(), "--out", out.toString()});

        List<String> summary = Files.readAllLines(out.resolve("summary.csv"));
        assertEquals(ResultWriter.SUMMARY_HEADER, summary.get(0));
        assertEquals(3, summary.size());
        assertTrue(summary.get(1).startsWith("1,"));
        assertTrue(summary.get(2).startsWith("2,"));

        List<String> raw = Files.readAllLines(out.resolve("raw.csv"));
        assertEquals(ResultWriter.RAW_HEADER, raw.get(0));
        assertTrue(raw.size() > 10);
        // Every request succeeded and returned the number of matches of the fixture.
        Map<String, Integer> expectedTotals = Map.of("adventure", 5, "river", 3, "moor", 2);
        for (String row : raw.subList(1, raw.size())) {
            String[] fields = row.split(",");
            assertEquals("200", fields[4], row);
            assertEquals(expectedTotals.get(fields[3]), Integer.valueOf(fields[7]), row);
        }

        String command = Files.readString(out.resolve("command.txt"));
        assertTrue(command.startsWith("java -jar benchmarks/load-client/target/load-client.jar --targets"));
        assertTrue(command.contains("model: closed loop"));
        String host = Files.readString(out.resolve("host.txt"));
        assertTrue(host.contains("cpu: ") && host.contains("logical_processors: ") && host.contains("java: "));
    }
}
