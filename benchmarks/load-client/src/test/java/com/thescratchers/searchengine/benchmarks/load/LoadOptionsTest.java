package com.thescratchers.searchengine.benchmarks.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadOptionsTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTheFixedQueryWorkloadOfTheCorpus() throws Exception {
        List<String> words = LoadOptions.readQueries(Path.of("..", "..", "samples", "words.txt"));

        assertEquals(List.of("time", "love", "heart", "nothing", "death", "truth", "fortune", "monster",
                "wonderland", "darcy"), words);
    }

    @Test
    void appliesTheDefaults() throws Exception {
        LoadOptions options = LoadOptions.parse("--queries", queries("time"));

        assertEquals(List.of("http://localhost:7003"), options.targets());
        assertEquals(List.of(1, 2, 4, 8, 16, 32, 64), options.clientLevels());
        assertEquals(Duration.ofSeconds(10), options.warmup());
        assertEquals(Duration.ofSeconds(30), options.duration());
        assertEquals("/search?q={q}", options.pathTemplate());
        assertTrue(options.outputDir().toString().endsWith("_search-load"));
        assertTrue(options.outputDir().startsWith(Path.of("benchmarks", "results")));
    }

    @Test
    void parsesEveryOption() throws Exception {
        LoadOptions options = LoadOptions.parse("--targets", "http://localhost:7003/, http://localhost:7013",
                "--clients", "1, 4", "--warmup", "0", "--duration", "5",
                "--path", "/search?q={q}&language=en", "--queries", queries("time", "love"),
                "--out", tempDir.resolve("run").toString());

        assertEquals(List.of("http://localhost:7003", "http://localhost:7013"), options.targets());
        assertEquals(List.of(1, 4), options.clientLevels());
        assertEquals(Duration.ZERO, options.warmup());
        assertEquals(Duration.ofSeconds(5), options.duration());
        assertEquals("/search?q={q}&language=en", options.pathTemplate());
        assertEquals(List.of("time", "love"), options.queries());
        assertEquals(tempDir.resolve("run"), options.outputDir());
    }

    @Test
    void rejectsInvalidOptions() throws Exception {
        String q = queries("time");
        assertThrows(IllegalArgumentException.class, () -> LoadOptions.parse("--queries", q, "--bogus", "1"));
        assertThrows(IllegalArgumentException.class, () -> LoadOptions.parse("--queries", q, "--clients"));
        assertThrows(IllegalArgumentException.class, () -> LoadOptions.parse("--queries", q, "--clients", "0"));
        assertThrows(IllegalArgumentException.class, () -> LoadOptions.parse("--queries", q, "--duration", "x"));
        assertThrows(IllegalArgumentException.class, () -> LoadOptions.parse("--queries", q, "--warmup", "-1"));
        assertThrows(IllegalArgumentException.class, () -> LoadOptions.parse("--queries", q, "--path", "/search"));
        assertThrows(IllegalArgumentException.class, () -> LoadOptions.parse("--queries", queries("# only a comment")));
    }

    private String queries(String... lines) throws Exception {
        Path file = Files.createTempFile(tempDir, "words", ".txt");
        Files.write(file, List.of(lines));
        return file.toString();
    }
}
