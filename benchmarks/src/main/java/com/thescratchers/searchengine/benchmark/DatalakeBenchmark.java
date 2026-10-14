package com.thescratchers.searchengine.benchmark;

import com.thescratchers.searchengine.benchmark.ContractDataset.RealBook;
import com.thescratchers.searchengine.datalake.DatalakeLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Stage 1 - Datalake benchmark (Java): compares the three datalake layouts
 * (time-based, book-based, batch-based) at the contract scales.
 *
 * Mirrors python/src/benchmark_datalake.py so results are comparable across
 * languages (shared/CONTRACT.md sections 4 and 6). For each structure and
 * scale it measures:
 *   1. write throughput        (seconds, books/sec)
 *   2. lookup cost             (200 random ids: resolve path + read body)
 *   3. storage overhead        (directories, max depth, files per directory)
 *   4. incremental processing  (detect which candidates are new, then write them)
 *   5. recovery                (crash after half, resume skipping existing files)
 *
 * Content: the 20 real books of shared/books.txt, replicated by POSITION
 * (CONTRACT.md 4.1): the book at 0-indexed position i gets the content of
 * real book books.txt[i % 20]. Synthetic ids start at 100000, like Python.
 *
 * Differences from the Python script, kept on purpose and documented in the
 * report: a short JVM warm-up pass is run first (discarded), and the TIME_BASED
 * hour directory is fixed once per structure run so the benchmark cannot break
 * if the clock crosses an hour boundary mid-run.
 *
 * The synthetic data is written to the system temp dir (outside the project/OneDrive
 * tree, like the Python benchmark's scratch folder); override with -Dbench.dir=<folder>.
 *
 * Run from the java/ directory:
 *   java -cp target/benchmarks.jar com.thescratchers.searchengine.benchmark.DatalakeBenchmark [scales_csv]
 * Default scales: 100,1000,10000. Output: datamarts/benchmark_datalake_results.json
 */
public class DatalakeBenchmark {

    static final Path BENCH_ROOT = scratchDir("bench_datalake");
    static final Path RESULTS_PATH = Paths.get("datamarts/benchmark_datalake_results.json");
    static final int START_ID = 100000;
    static final int N_LOOKUPS = 200;
    static final int WARMUP_BOOKS = 200;
    static final long SEED = 42;

    /** Keeps the lookup reads observable so the JIT cannot discard them. */
    private static volatile long bytesReadSink;

    private DatalakeBenchmark() {}

    public static void main(String[] args) throws Exception {
        String scalesCsv = args.length > 0 ? args[0] : "100,1000,10000";
        List<Integer> scales = parseScales(scalesCsv);

        System.out.println("Scratch directory for synthetic data: " + BENCH_ROOT.toAbsolutePath());
        System.out.println("Loading real content from " + ContractDataset.DATALAKE_ROOT + "...");
        List<Integer> realIds = ContractDataset.loadBookIds();
        Map<Integer, RealBook> realBooks = ContractDataset.loadRealBooks();
        System.out.println("Loaded " + realBooks.size() + " contract books.");

        try {
            // JVM warm-up (JIT, class loading, filesystem caches); results discarded.
            System.out.println("\nJVM warm-up (" + WARMUP_BOOKS + " books per structure, discarded)...");
            Random warmRandom = new Random(SEED);
            for (DatalakeLayout layout : DatalakeLayout.values()) {
                benchmarkStructure(layout, makeSyntheticIds(WARMUP_BOOKS), realIds, realBooks, warmRandom, false);
            }

            Random random = new Random(SEED);
            Map<String, Object> resultsByScale = new LinkedHashMap<>();
            for (int nBooks : scales) {
                System.out.println("\n=== Benchmarking " + nBooks + " synthetic books across 3 datalake structures ===");
                List<Integer> bookIds = makeSyntheticIds(nBooks);

                List<Map<String, Object>> results = new ArrayList<>();
                for (DatalakeLayout layout : DatalakeLayout.values()) {
                    results.add(benchmarkStructure(layout, bookIds, realIds, realBooks, random, true));
                }
                printTable(nBooks, results);
                resultsByScale.put(String.valueOf(nBooks), results);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scales", scales);
            out.put("results_by_scale", resultsByScale);

            if (RESULTS_PATH.getParent() != null) Files.createDirectories(RESULTS_PATH.getParent());
            Files.writeString(RESULTS_PATH, JsonOut.toJson(out));
            System.out.println("\nResults written to " + RESULTS_PATH);
        } finally {
            // Synthetic data: no need to keep it around. Never throws, so a cleanup
            // problem cannot hide the real error of the run.
            cleanupQuietly(BENCH_ROOT);
        }
    }

    static Map<String, Object> benchmarkStructure(DatalakeLayout layout, List<Integer> bookIds,
                                                  List<Integer> realIds, Map<Integer, RealBook> realBooks,
                                                  Random random, boolean verbose) throws IOException {
        String name = layout.label();
        Path baseDir = BENCH_ROOT.resolve(name);
        int n = bookIds.size();
        int cycle = realIds.size();

        // Start from a clean, empty directory so leftovers of a previous run cannot skew results.
        deleteRecursively(baseDir);
        Files.createDirectories(baseDir);

        // The ingestion moment is fixed for the whole run: TIME_BASED puts every book
        // in the same yyyyMMdd/HH directory (what the Downloader does within one hour).
        LocalDateTime when = LocalDateTime.now();

        // 1. Write throughput
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            int bookId = bookIds.get(i);
            // CONTRACT.md 4.1: the i-th book (0-indexed POSITION) replicates real book books.txt[i % 20].
            RealBook content = realBooks.get(realIds.get(i % cycle));
            DatalakeLayout.saveBook(layout.directoryFor(baseDir, bookId, when), bookId,
                    content.header(), content.body());
            if (verbose && (i + 1) % 2000 == 0) {
                System.out.println("    [" + name + "] ...wrote " + (i + 1) + "/" + n + " books");
            }
        }
        double writeElapsed = seconds(System.nanoTime() - t0);

        // 2. Lookup cost: N random ids, resolve the path again (no caching) and read the body.
        List<Integer> sampleIds = sample(bookIds, Math.min(N_LOOKUPS, n), random);
        t0 = System.nanoTime();
        long bytesRead = 0;
        for (int bookId : sampleIds) {
            Path dir = layout.directoryFor(baseDir, bookId, when);
            bytesRead += Files.readString(DatalakeLayout.bodyFile(dir, bookId)).length();
        }
        double lookupElapsed = seconds(System.nanoTime() - t0);
        bytesReadSink = bytesRead;

        // 3. Storage overhead
        TreeStats stats = treeStats(baseDir);
        double avgFilesPerDir = stats.bodyFilesPerDir.isEmpty() ? 0
                : (double) stats.totalBodyFiles() / stats.bodyFilesPerDir.size();
        int maxFilesPerDir = stats.bodyFilesPerDir.values().stream().max(Integer::compare).orElse(0);

        // 4. Incremental processing: candidates = already-present ids (second half) plus
        // n/10 genuinely new ids; first DETECT which are new, then write only those.
        int incrementalN = Math.max(1, n / 10);
        int lastId = n > 0 ? bookIds.get(n - 1) : -1;
        int halfPoint = n / 2;
        List<Integer> candidates = new ArrayList<>(bookIds.subList(halfPoint, n));
        for (int k = 1; k <= incrementalN; k++) candidates.add(lastId + k);

        t0 = System.nanoTime();
        // (position, bookId): candidate j sits at position halfPoint + j, so the same
        // "position % 20" content rule extends seamlessly to the new ids.
        List<int[]> newEntries = new ArrayList<>();
        for (int j = 0; j < candidates.size(); j++) {
            int bookId = candidates.get(j);
            Path dir = layout.directoryFor(baseDir, bookId, when);
            if (!Files.exists(DatalakeLayout.bodyFile(dir, bookId))) {
                newEntries.add(new int[] {halfPoint + j, bookId});
            }
        }
        double incrementalDetectElapsed = seconds(System.nanoTime() - t0);

        t0 = System.nanoTime();
        for (int[] entry : newEntries) {
            RealBook content = realBooks.get(realIds.get(entry[0] % cycle));
            DatalakeLayout.saveBook(layout.directoryFor(baseDir, entry[1], when), entry[1],
                    content.header(), content.body());
        }
        double incrementalWriteElapsed = seconds(System.nanoTime() - t0);

        // 5. Recovery: wipe, write only the "pre-crash" first half, then resume over the FULL
        // list skipping ids whose body file already exists (idempotent recovery).
        deleteRecursively(baseDir);
        Files.createDirectories(baseDir);
        int half = n / 2;
        for (int i = 0; i < half; i++) {
            int bookId = bookIds.get(i);
            RealBook content = realBooks.get(realIds.get(i % cycle));
            DatalakeLayout.saveBook(layout.directoryFor(baseDir, bookId, when), bookId,
                    content.header(), content.body());
        }

        t0 = System.nanoTime();
        int resumedCount = 0;
        for (int i = 0; i < n; i++) {
            int bookId = bookIds.get(i);
            Path dir = layout.directoryFor(baseDir, bookId, when);
            if (Files.exists(DatalakeLayout.bodyFile(dir, bookId))) continue; // written before the "crash"
            RealBook content = realBooks.get(realIds.get(i % cycle));
            DatalakeLayout.saveBook(dir, bookId, content.header(), content.body());
            resumedCount++;
        }
        double recoveryElapsed = seconds(System.nanoTime() - t0);

        // Correctness: exactly n body files afterwards - nothing lost, nothing duplicated.
        boolean recoveryOk = treeStats(baseDir).totalBodyFiles() == n;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("structure", name);
        r.put("n_books", n);
        r.put("write_seconds", JsonOut.round(writeElapsed, 4));
        r.put("write_books_per_sec", writeElapsed > 0 ? JsonOut.round(n / writeElapsed, 1) : null);
        r.put("lookup_n", sampleIds.size());
        r.put("lookup_seconds", JsonOut.round(lookupElapsed, 4));
        r.put("lookup_avg_ms", sampleIds.isEmpty() ? null
                : JsonOut.round(lookupElapsed / sampleIds.size() * 1000, 4));
        r.put("num_dirs_created", stats.numDirs);
        r.put("max_depth", stats.maxDepth);
        r.put("avg_files_per_dir", JsonOut.round(avgFilesPerDir, 1));
        r.put("max_files_per_dir", maxFilesPerDir);
        r.put("incremental_candidates", candidates.size());
        r.put("incremental_new_found", newEntries.size());
        r.put("incremental_detect_seconds", JsonOut.round(incrementalDetectElapsed, 4));
        r.put("incremental_write_seconds", JsonOut.round(incrementalWriteElapsed, 4));
        r.put("recovery_resumed_count", resumedCount);
        r.put("recovery_seconds", JsonOut.round(recoveryElapsed, 4));
        r.put("recovery_ok", recoveryOk);
        return r;
    }

    /** Synthetic ids that cannot collide with real Gutenberg ids. */
    static List<Integer> makeSyntheticIds(int nBooks) {
        return IntStream.range(START_ID, START_ID + nBooks).boxed().collect(Collectors.toList());
    }

    /** k distinct ids, chosen uniformly (like Python's random.sample). */
    static List<Integer> sample(List<Integer> ids, int k, Random random) {
        List<Integer> copy = new ArrayList<>(ids);
        Collections.shuffle(copy, random);
        return new ArrayList<>(copy.subList(0, k));
    }

    static List<Integer> parseScales(String csv) {
        List<Integer> scales = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) scales.add(Integer.parseInt(part.trim()));
        }
        if (scales.isEmpty()) throw new IllegalArgumentException("No scales given: '" + csv + "'");
        return scales;
    }

    static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    /** Directory count, deepest nesting and *.body.txt files per directory under a root. */
    static final class TreeStats {
        int numDirs;
        int maxDepth;
        final Map<Path, Integer> bodyFilesPerDir = new HashMap<>();

        int totalBodyFiles() {
            return bodyFilesPerDir.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    static TreeStats treeStats(Path root) throws IOException {
        TreeStats stats = new TreeStats();
        if (!Files.exists(root)) return stats;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(root)) continue; // like Python's rglob, the root itself is not counted
                stats.maxDepth = Math.max(stats.maxDepth, root.relativize(p).getNameCount());
                if (Files.isDirectory(p)) {
                    stats.numDirs++;
                } else if (p.getFileName().toString().endsWith(".body.txt")) {
                    stats.bodyFilesPerDir.merge(p.getParent(), 1, Integer::sum);
                }
            }
        }
        return stats;
    }

    /**
     * Scratch directory for the synthetic data of a benchmark. It lives OUTSIDE the project
     * tree (system temp dir, or -Dbench.dir=...): inside OneDrive/Dropbox-synced folders,
     * Windows sync clients and antivirus hold freshly created files and make deletes fail
     * with AccessDeniedException, and they would also distort the I/O measurements.
     */
    static Path scratchDir(String name) {
        String base = System.getProperty("bench.dir", System.getProperty("java.io.tmpdir"));
        return Paths.get(base, "thescratchers_bench", name);
    }

    private static final int DELETE_ATTEMPTS = 10;
    private static final long DELETE_RETRY_DELAY_MS = 500;

    /** Deletes a directory tree, retrying a few times (Windows may briefly lock fresh files). */
    static void deleteRecursively(Path root) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                deleteTree(root);
                return;
            } catch (IOException e) {
                if (attempt >= DELETE_ATTEMPTS) throw e;
                try {
                    Thread.sleep(DELETE_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path p : paths) Files.deleteIfExists(p);
    }

    /** Best-effort cleanup: prints a warning instead of failing. */
    static void cleanupQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException e) {
            System.err.println("[WARN] Could not delete scratch directory " + root.toAbsolutePath()
                    + " (" + e + "). It only holds synthetic data and can be deleted manually.");
        }
    }

    static void printTable(int scale, List<Map<String, Object>> results) {
        System.out.println("\n--- " + scale + " books ---");
        System.out.printf("%-14s%-12s%-10s%-18s%-8s%-10s%-14s%-12s%n",
                "Structure", "Write (s)", "Books/s", "Lookup avg (ms)", "#Dirs", "MaxDepth", "AvgFiles/Dir", "MaxFiles/Dir");
        for (Map<String, Object> r : results) {
            System.out.printf("%-14s%-12s%-10s%-18s%-8s%-10s%-14s%-12s%n",
                    r.get("structure"), r.get("write_seconds"), r.get("write_books_per_sec"),
                    r.get("lookup_avg_ms"), r.get("num_dirs_created"), r.get("max_depth"),
                    r.get("avg_files_per_dir"), r.get("max_files_per_dir"));
        }
        System.out.printf("%n%-14s%-18s%-18s%-14s%-12s%n",
                "Structure", "Incr. detect (s)", "Incr. write (s)", "Recovery (s)", "Recovery OK");
        for (Map<String, Object> r : results) {
            System.out.printf("%-14s%-18s%-18s%-14s%-12s%n",
                    r.get("structure"), r.get("incremental_detect_seconds"),
                    r.get("incremental_write_seconds"), r.get("recovery_seconds"), r.get("recovery_ok"));
        }
    }
}
