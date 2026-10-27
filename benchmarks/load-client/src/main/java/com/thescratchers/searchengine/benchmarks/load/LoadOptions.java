package com.thescratchers.searchengine.benchmarks.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings of one load-test run, parsed from {@code --name value} command-line arguments.
 *
 * @param targets      base URLs of the Search replicas; requests are spread over them round-robin
 * @param clientLevels numbers of concurrent clients to run, one level after another
 * @param warmup       time each level runs before samples are recorded
 * @param duration     time each level is measured
 * @param pathTemplate request path with a {@code {q}} placeholder for the URL-encoded query word
 * @param queriesFile  file with one query word per line ({@code #} comments and blank lines skipped)
 * @param queries      the words read from {@code queriesFile}
 * @param outputDir    directory where the raw results, summary, command and host description go
 */
public record LoadOptions(List<String> targets, List<Integer> clientLevels, Duration warmup, Duration duration,
                          String pathTemplate, Path queriesFile, List<String> queries, Path outputDir) {

    static final String USAGE = """
            Usage: java -jar benchmarks/load-client/target/load-client.jar [options]
              --targets   URL[,URL...]   Search replicas, round-robin      (default http://localhost:7003)
              --clients   N[,N...]       concurrency levels                (default 1,2,4,8,16,32,64)
              --warmup    SECONDS        warm-up per level, not recorded   (default 10)
              --duration  SECONDS        measurement per level             (default 30)
              --path      TEMPLATE       request path, {q} = query word    (default /search?q={q})
              --queries   FILE           one query word per line           (default samples/words.txt)
              --out       DIR            results directory                 (default benchmarks/results/<UTC time>_search-load)
            """;

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public LoadOptions {
        targets = List.copyOf(targets);
        clientLevels = List.copyOf(clientLevels);
        queries = List.copyOf(queries);
    }

    /**
     * Parses the command-line arguments, applying the defaults above, and reads the query file.
     *
     * @throws IllegalArgumentException if an option is unknown, has no value or has an invalid value
     * @throws IOException              if the query file cannot be read
     */
    public static LoadOptions parse(String... args) throws IOException {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("every option needs a value");
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            if (!List.of("--targets", "--clients", "--warmup", "--duration", "--path", "--queries", "--out")
                    .contains(name)) {
                throw new IllegalArgumentException("unknown option " + name);
            }
            values.put(name, args[i + 1]);
        }

        List<String> targets = new ArrayList<>();
        for (String target : values.getOrDefault("--targets", "http://localhost:7003").split(",")) {
            String trimmed = target.strip();
            targets.add(trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
        }
        List<Integer> levels = Arrays.stream(values.getOrDefault("--clients", "1,2,4,8,16,32,64").split(","))
                .map(String::strip).map(level -> positive("--clients", level)).toList();
        Duration warmup = Duration.ofSeconds(nonNegative("--warmup", values.getOrDefault("--warmup", "10")));
        Duration duration = Duration.ofSeconds(positive("--duration", values.getOrDefault("--duration", "30")));
        String path = values.getOrDefault("--path", "/search?q={q}");
        if (!path.startsWith("/") || !path.contains("{q}")) {
            throw new IllegalArgumentException("--path must start with / and contain {q}");
        }
        Path queriesFile = Path.of(values.getOrDefault("--queries", "samples/words.txt"));
        List<String> queries = readQueries(queriesFile);
        Path out = values.containsKey("--out")
                ? Path.of(values.get("--out"))
                : Path.of("benchmarks", "results", ZonedDateTime.now(ZoneOffset.UTC).format(RUN_ID) + "_search-load");
        return new LoadOptions(targets, levels, warmup, duration, path, queriesFile, queries, out);
    }

    /** Reads one query word per line, skipping blank lines and {@code #} comments. */
    static List<String> readQueries(Path file) throws IOException {
        List<String> queries = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("no queries in " + file);
        }
        return queries;
    }

    private static int positive(String option, String value) {
        int number = nonNegative(option, value);
        if (number == 0) {
            throw new IllegalArgumentException(option + " must be positive");
        }
        return number;
    }

    private static int nonNegative(String option, String value) {
        try {
            int number = Integer.parseInt(value);
            if (number < 0) {
                throw new IllegalArgumentException(option + " must not be negative");
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " must be an integer but was " + value);
        }
    }
}
