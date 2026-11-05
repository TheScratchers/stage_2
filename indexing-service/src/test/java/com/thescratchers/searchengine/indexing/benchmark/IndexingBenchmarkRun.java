package com.thescratchers.searchengine.indexing.benchmark;

import com.thescratchers.searchengine.common.Sqlite;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Runs every Indexing microbenchmark of the report with one command and writes one results
 * directory (corrections 4, 6, 7 and 8 of Stage 1):
 * <ul>
 *   <li>{@code jmh.csv}: raw JMH results of {@link IndexingBenchmark} and {@link CorpusIndexingBenchmark},
 *       with the allocation figures of the GC profiler; {@code jmh.txt}: the full JMH log.</li>
 *   <li>{@code footprint.csv}: disk and heap footprint of indexing the corpus ({@link IndexFootprint}).</li>
 *   <li>{@code command.txt}: exact command, commit, JMH, JVM and SQLite settings; {@code host.txt}: the machine.</li>
 * </ul>
 *
 * <p>From the repository root, on the reference machine:
 * <pre>
 * mvn -q install -DskipTests
 * mvn -q -pl indexing-service exec:exec -Dexec.executable=java -Dexec.classpathScope=test "-Dexec.args=-Xms2G -Xmx2G -classpath %classpath com.thescratchers.searchengine.indexing.benchmark.IndexingBenchmarkRun"
 * </pre>
 * Options, appended after the class name: {@code --quick} (1 warm-up and 2 measured iterations of
 * 1 s, results in {@code indexing-service/target/jmh-trial} unless {@code --out} is given) and
 * {@code --out DIR}. By default the results go to {@code benchmarks/results/<yyyyMMdd>_indexing-jmh};
 * an existing, non-empty results directory is never overwritten.
 */
public final class IndexingBenchmarkRun {

    /** The per-book log lines of the indexer would flood the console while measuring the footprint. */
    static {
        System.setProperty("org.slf4j.simpleLogger.log.com.thescratchers.searchengine.indexing.BookIndexer", "warn");
    }

    private static final Logger LOG = LoggerFactory.getLogger(IndexingBenchmarkRun.class);

    private static final String COMMAND = "mvn -q install -DskipTests\n"
            + "mvn -q -pl indexing-service exec:exec -Dexec.executable=java -Dexec.classpathScope=test "
            + "\"-Dexec.args=-Xms2G -Xmx2G -classpath %classpath " + IndexingBenchmarkRun.class.getName();
    private static final Path RESULTS = Path.of("..", "benchmarks", "results");
    private static final Path TRIAL_RESULTS = Path.of("target", "jmh-trial");
    private static final String EXPERIMENT = "_indexing-jmh";

    private IndexingBenchmarkRun() {
    }

    /** Runs the benchmarks; see the class comment for the options. */
    public static void main(String[] args) throws Exception {
        boolean quick = false;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--out" -> {
                    if (i + 1 == args.length) {
                        throw new IllegalArgumentException("--out needs a directory");
                    }
                    out = Path.of(args[++i]);
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]
                        + "; expected --quick and/or --out DIR");
            }
        }
        if (out == null) {
            out = quick ? TRIAL_RESULTS : RESULTS.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + EXPERIMENT);
        }
        run(out.toAbsolutePath().normalize(), quick, String.join(" ", args));
    }

    private static void run(Path out, boolean quick, String options) throws Exception {
        // JMH formats the CSV with the default locale; a Spanish one would write decimal commas.
        Locale.setDefault(Locale.ROOT);
        requireEmpty(out);
        Files.createDirectories(out);
        Instant start = Instant.now();
        LOG.info("Writing {} results to {}", quick ? "trial" : "official", out);
        Files.writeString(out.resolve("host.txt"), HostInfo.describe(), StandardCharsets.UTF_8);

        LOG.info("Measuring the disk and heap footprint of indexing the corpus");
        IndexFootprint.measure(out.resolve("footprint.csv"));

        LOG.info("Running JMH; the official run takes about 15 minutes");
        ChainedOptionsBuilder jmh = new OptionsBuilder()
                .include(IndexingBenchmark.class.getName() + "\\.")
                .include(CorpusIndexingBenchmark.class.getName() + "\\.")
                .addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.CSV)
                .result(out.resolve("jmh.csv").toString())
                .output(out.resolve("jmh.txt").toString());
        if (quick) {
            jmh.warmupIterations(1).measurementIterations(2)
                    .warmupTime(TimeValue.seconds(1)).measurementTime(TimeValue.seconds(1));
        }
        new Runner(jmh.build()).run();

        Files.writeString(out.resolve("command.txt"), command(quick, options, start), StandardCharsets.UTF_8);
        LOG.info("Done: {}", out);
    }

    private static void requireEmpty(Path out) throws IOException {
        if (!Files.isDirectory(out)) {
            return;
        }
        try (Stream<Path> files = Files.list(out)) {
            if (files.findAny().isPresent()) {
                throw new IllegalStateException(out + " already has results; choose another directory with --out");
            }
        }
    }

    private static String command(boolean quick, String options, Instant start) throws IOException {
        String jvm = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
        return "command (from the repository root):\n" + COMMAND + (options.isEmpty() ? "" : " " + options) + "\"\n"
                + "mode: " + (quick ? "trial (--quick): not valid for the report" : "official") + "\n"
                + "commit: " + HostInfo.gitCommit() + "\n"
                + "started_at: " + start + "\n"
                + "finished_at: " + Instant.now() + "\n"
                + "corpus: samples/books.txt, " + Corpus.bookIds().size() + " books, "
                + Corpus.sizeBytes(Corpus.bookIds()) + " bytes of header and body files\n"
                + "jmh: 1.37, one fork per benchmark, profiler gc (allocation per operation)\n"
                + "jmh_IndexingBenchmark: average time per operation on book 1342; "
                + (quick ? "1 warm-up and 2 measured iterations of 1 s" : "5 warm-up and 10 measured iterations of 10 s")
                + "; fork -Xms2G -Xmx2G\n"
                + "jmh_CorpusIndexingBenchmark: single-shot time of one pass over the whole corpus; "
                + (quick ? "1 warm-up and 2 measured operations" : "5 warm-up and 10 measured operations")
                + "; time limit 2 min per operation; threads 1, 2, 4; fork -Xms2G -Xmx2G, log level warn\n"
                + "sqlite: journal_mode=WAL, synchronous=NORMAL, busy_timeout=" + Sqlite.BUSY_TIMEOUT_MS
                + " ms, default page cache; datamarts in temporary directories of the system disk\n"
                + "footprint_jvm: " + jvm + " (max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB)\n"
                + "number_format: '.' as decimal separator (Locale.ROOT)\n"
                + "files: jmh.csv (raw JMH results), jmh.txt (JMH log), footprint.csv, host.txt\n";
    }
}
