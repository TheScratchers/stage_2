package com.thescratchers.searchengine.benchmark;

import com.thescratchers.searchengine.benchmark.ContractDataset.RealBook;
import com.thescratchers.searchengine.datamarts.MetadataExtractor;
import com.thescratchers.searchengine.datamarts.MetadataExtractor.BookRecord;
import com.thescratchers.searchengine.datamarts.MetadataExtractor.ParsedHeader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Stage 1 - Metadata benchmark (Java): stress-tests the SQLite metadata
 * datamart at increasing scale. Team extension (shared/CONTRACT.md section 5),
 * mirrors python/src/benchmark_metadata.py.
 *
 * Per scale (100 / 1,000 / 10,000 / 100,000 books):
 *   1. insertion speed        (upsert N rows, one connection per call)
 *   2. find_by_id             (average over 200 random ids)
 *   3. find_by_author         (30 repetitions each, timed separately):
 *        repeated author "Charles Dickens" (books 98, 46, 1400 of the 20)
 *        unique author   "Lewis Carroll"   (book 11 of the 20)
 *   4. database size on disk
 *
 * The title/author/language of the 20 real contract books (parsed from their
 * headers with the same regexes the real pipeline uses) are replicated by
 * POSITION (CONTRACT.md 4.1): row i gets the metadata of books.txt[i % 20].
 * Synthetic ids start at 500000, like Python.
 *
 * It calls the real MetadataExtractor methods (upsertBook / findById /
 * findByAuthor), including their per-call connect/commit/close overhead, so it
 * measures our actual implementation, not an idealized bulk insert.
 *
 * Note: the insert of 100,000 rows with one auto-committed transaction per row
 * is slow by design (Python needs ~9 minutes); expect a long run at that scale.
 *
 * Synthetic databases are created in the system temp dir (outside the project/OneDrive tree);
 * override with -Dbench.dir=<folder>.
 *
 * Run from the java/ directory:
 *   java -cp target/benchmarks.jar com.thescratchers.searchengine.benchmark.MetadataBenchmark [scales_csv]
 * Default scales: 100,1000,10000,100000. Output: datamarts/benchmark_metadata_results.json
 */
public class MetadataBenchmark {

    static final Path BENCH_ROOT = DatalakeBenchmark.scratchDir("bench_metadata");
    static final Path RESULTS_PATH = Paths.get("datamarts/benchmark_metadata_results.json");
    static final int START_ID = 500000;
    static final int N_ID_LOOKUPS = 200;
    static final int AUTHOR_QUERY_REPS = 30;
    static final int WARMUP_ROWS = 200;
    static final long SEED = 42;
    static final String REPEATED_AUTHOR = "Charles Dickens";
    static final String UNIQUE_AUTHOR = "Lewis Carroll";

    /** Creates the extractor for a scratch DB file; replaceable only by the smoke test. */
    static Function<String, MetadataExtractor> extractorFactory = MetadataExtractor::new;

    /** Keeps query results observable so the JIT cannot discard them. */
    private static volatile int resultSink;

    private MetadataBenchmark() {}

    public static void main(String[] args) throws Exception {
        String scalesCsv = args.length > 0 ? args[0] : "100,1000,10000,100000";
        List<Integer> scales = DatalakeBenchmark.parseScales(scalesCsv);

        System.out.println("Scratch directory for the synthetic databases: " + BENCH_ROOT.toAbsolutePath());
        System.out.println("Loading real metadata from " + ContractDataset.DATALAKE_ROOT + "...");
        List<Integer> realIds = ContractDataset.loadBookIds();
        Map<Integer, ParsedHeader> metaByRealId = new LinkedHashMap<>();
        for (Map.Entry<Integer, RealBook> e : ContractDataset.loadRealBooks().entrySet()) {
            metaByRealId.put(e.getKey(), MetadataExtractor.parseHeader(e.getValue().header()));
        }
        System.out.println("find_by_author queries: repeated='" + REPEATED_AUTHOR + "', unique='" + UNIQUE_AUTHOR + "'");

        Files.createDirectories(BENCH_ROOT);
        try {
            // JVM + JDBC driver warm-up (native library load, JIT); results discarded.
            System.out.println("\nWarm-up (" + WARMUP_ROWS + " rows, discarded)...");
            benchmarkScale("warmup", WARMUP_ROWS, realIds, metaByRealId, new Random(SEED), false);

            Random random = new Random(SEED);
            System.out.println("\nBenchmarking SQLite metadata datamart at scales: " + scales + "\n");
            List<Map<String, Object>> results = new ArrayList<>();
            for (int n : scales) {
                System.out.println("-- " + n + " books --");
                results.add(benchmarkScale("metadata_" + n, n, realIds, metaByRealId, random, true));
            }

            printTable(results);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scales", scales);
            out.put("results", results);
            if (RESULTS_PATH.getParent() != null) Files.createDirectories(RESULTS_PATH.getParent());
            Files.writeString(RESULTS_PATH, JsonOut.toJson(out));
            System.out.println("\nResults written to " + RESULTS_PATH);
        } finally {
            // Synthetic databases: only the results JSON survives. Never throws, so a
            // cleanup problem cannot hide the real error of the run.
            DatalakeBenchmark.cleanupQuietly(BENCH_ROOT);
        }
    }

    static Map<String, Object> benchmarkScale(String dbName, int nBooks, List<Integer> realIds,
                                              Map<Integer, ParsedHeader> metaByRealId,
                                              Random random, boolean verbose) throws Exception {
        Path dbFile = BENCH_ROOT.resolve(dbName + ".db");
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(BENCH_ROOT.resolve(dbName + ".db-journal"));
        MetadataExtractor extractor = extractorFactory.apply(dbFile.toString());

        List<Integer> bookIds = IntStream.range(START_ID, START_ID + nBooks).boxed().collect(Collectors.toList());
        int cycle = realIds.size();

        // 1. Insertion speed
        long t0 = System.nanoTime();
        for (int i = 0; i < nBooks; i++) {
            // CONTRACT.md 4.1: row i (0-indexed POSITION) replicates real book books.txt[i % 20].
            ParsedHeader meta = metaByRealId.get(realIds.get(i % cycle));
            extractor.upsertBook(bookIds.get(i), meta.title(), meta.author(), meta.language());
            if (verbose && (i + 1) % 10000 == 0) {
                System.out.println("  ...inserted " + (i + 1) + "/" + nBooks);
            }
        }
        double insertElapsed = DatalakeBenchmark.seconds(System.nanoTime() - t0);

        // 2. find_by_id (primary-key lookup) over random distinct ids
        List<Integer> shuffled = new ArrayList<>(bookIds);
        Collections.shuffle(shuffled, random);
        List<Integer> sampleIds = shuffled.subList(0, Math.min(N_ID_LOOKUPS, nBooks));
        int found = 0;
        t0 = System.nanoTime();
        for (int bookId : sampleIds) {
            if (extractor.findById(bookId).isPresent()) found++;
        }
        double idLookupElapsed = DatalakeBenchmark.seconds(System.nanoTime() - t0);
        if (found != sampleIds.size()) {
            throw new IllegalStateException("find_by_id found " + found + " of " + sampleIds.size() + " existing ids");
        }

        // 2b. find_by_author, the two fixed contract authors, timed separately
        int repeatedRows = 0;
        t0 = System.nanoTime();
        for (int rep = 0; rep < AUTHOR_QUERY_REPS; rep++) {
            repeatedRows = extractor.findByAuthor(REPEATED_AUTHOR).size();
        }
        double repeatedAuthorElapsed = DatalakeBenchmark.seconds(System.nanoTime() - t0);

        int uniqueRows = 0;
        t0 = System.nanoTime();
        for (int rep = 0; rep < AUTHOR_QUERY_REPS; rep++) {
            uniqueRows = extractor.findByAuthor(UNIQUE_AUTHOR).size();
        }
        double uniqueAuthorElapsed = DatalakeBenchmark.seconds(System.nanoTime() - t0);
        resultSink = repeatedRows + uniqueRows;

        // Correctness: the queries must return exactly the rows that carry that author.
        int expectedRepeated = expectedRows(REPEATED_AUTHOR, nBooks, realIds, metaByRealId);
        int expectedUnique = expectedRows(UNIQUE_AUTHOR, nBooks, realIds, metaByRealId);
        if (repeatedRows != expectedRepeated || uniqueRows != expectedUnique) {
            throw new IllegalStateException("find_by_author returned " + repeatedRows + "/" + uniqueRows
                    + " rows, expected " + expectedRepeated + "/" + expectedUnique + " (scale " + nBooks + ")");
        }

        long dbSizeBytes = Files.size(dbFile);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("n_books", nBooks);
        r.put("insert_seconds", JsonOut.round(insertElapsed, 4));
        r.put("insert_books_per_sec", insertElapsed > 0 ? JsonOut.round(nBooks / insertElapsed, 1) : null);
        r.put("find_by_id_avg_ms", sampleIds.isEmpty() ? null
                : JsonOut.round(idLookupElapsed / sampleIds.size() * 1000, 4));
        r.put("find_by_author_repeated_avg_ms", JsonOut.round(repeatedAuthorElapsed / AUTHOR_QUERY_REPS * 1000, 4));
        r.put("find_by_author_unique_avg_ms", JsonOut.round(uniqueAuthorElapsed / AUTHOR_QUERY_REPS * 1000, 4));
        r.put("db_size_bytes", dbSizeBytes);
        return r;
    }

    /** How many of the first nBooks positions carry an author containing the query (case-insensitive). */
    static int expectedRows(String authorQuery, int nBooks, List<Integer> realIds,
                            Map<Integer, ParsedHeader> metaByRealId) {
        String needle = authorQuery.toLowerCase();
        int count = 0;
        for (int i = 0; i < nBooks; i++) {
            String author = metaByRealId.get(realIds.get(i % realIds.size())).author();
            if (author != null && author.toLowerCase().contains(needle)) count++;
        }
        return count;
    }

    static void printTable(List<Map<String, Object>> results) throws IOException {
        System.out.printf("%n%-12s%-12s%-10s%-18s%-22s%-20s%-14s%n", "N books", "Insert (s)", "Books/s",
                "find_by_id (ms)", "author:repeated (ms)", "author:unique (ms)", "DB size (KB)");
        for (Map<String, Object> r : results) {
            System.out.printf("%-12s%-12s%-10s%-18s%-22s%-20s%-14s%n", r.get("n_books"), r.get("insert_seconds"),
                    r.get("insert_books_per_sec"), r.get("find_by_id_avg_ms"),
                    r.get("find_by_author_repeated_avg_ms"), r.get("find_by_author_unique_avg_ms"),
                    JsonOut.round(((Long) r.get("db_size_bytes")) / 1024.0, 1));
        }
    }
}
