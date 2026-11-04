package com.thescratchers.searchengine.indexing.benchmark;

import com.thescratchers.searchengine.common.Sqlite;
import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.store.DatamartStore;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Disk and memory footprint of indexing the fixed corpus (correction 6 of Stage 1, component
 * level; the resident memory of the running processes is measured by the integration benchmarks).
 *
 * <p>For 1, 2 and 4 threads the corpus is indexed into an empty temporary datamart. Recorded, with
 * what each figure includes:
 * <ul>
 *   <li>Disk: the corpus files, and {@code datamart.db} after a {@code wal_checkpoint(TRUNCATE)},
 *       so that the WAL is empty and the file holds the whole index (tables and the postings
 *       index); the number of {@code books} and {@code postings} rows.</li>
 *   <li>Memory: Java heap in use after a GC before and after indexing, and the peak heap while
 *       indexing (sum of the peaks of every heap pool, so an upper bound that depends on the GC).
 *       The SQLite page cache is native memory and is not included.</li>
 * </ul>
 */
final class IndexFootprint {

    private static final int[] THREADS = {1, 2, 4};
    private static final String HEADER = "threads,metric,value,unit,includes";

    private IndexFootprint() {
    }

    /** Measures every configuration and writes one CSV row per figure to {@code csv}. */
    static void measure(Path csv) throws Exception {
        List<Integer> bookIds = Corpus.bookIds();
        List<String> rows = new ArrayList<>();
        rows.add(HEADER);
        for (int threads : THREADS) {
            rows.addAll(measure(bookIds, threads));
        }
        Files.write(csv, rows, StandardCharsets.UTF_8);
    }

    private static List<String> measure(List<Integer> bookIds, int threads) throws Exception {
        List<String> rows = new ArrayList<>();
        try (TempDirectory dir = TempDirectory.create()) {
            Path db = dir.datamart();
            BookIndexer indexer = new BookIndexer(new DatamartStore(db));

            long heapBefore = heapUsedAfterGc();
            resetHeapPeaks();
            indexCorpus(bookIds, indexer, threads);
            long heapPeak = heapPeak();
            long heapAfter = heapUsedAfterGc();

            checkpoint(db);

            rows.add(row(threads, "corpus_books", bookIds.size(), "books", "books in samples/books.txt"));
            rows.add(row(threads, "corpus_bytes", Corpus.sizeBytes(bookIds), "bytes",
                    "header and body files of the corpus books"));
            rows.add(row(threads, "books_rows", count(db, "books"), "rows", "rows of the books table"));
            rows.add(row(threads, "postings_rows", count(db, "postings"), "rows", "rows of the postings table"));
            rows.add(row(threads, "datamart_db_bytes", Files.size(db), "bytes",
                    "datamart.db after wal_checkpoint(TRUNCATE): tables and the postings index"));
            rows.add(row(threads, "heap_used_before_bytes", heapBefore, "bytes", "Java heap in use after a GC"));
            rows.add(row(threads, "heap_peak_bytes", heapPeak, "bytes",
                    "sum of the peak use of every heap pool while indexing; excludes SQLite native memory"));
            rows.add(row(threads, "heap_used_after_bytes", heapAfter, "bytes", "Java heap in use after a GC"));
        }
        return rows;
    }

    private static void indexCorpus(List<Integer> bookIds, BookIndexer indexer, int threads)
            throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<BookIndexer.IndexResult>> tasks = new ArrayList<>();
            for (int bookId : bookIds) {
                tasks.add(() -> indexer.index(bookId, Corpus.header(bookId), Corpus.body(bookId)));
            }
            for (Future<BookIndexer.IndexResult> result : pool.invokeAll(tasks)) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static long heapUsedAfterGc() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    private static long heapPeak() {
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }

    private static void checkpoint(Path db) throws SQLException {
        try (Connection conn = Sqlite.open(db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private static long count(Path db, String table) throws SQLException {
        try (Connection conn = Sqlite.openReadOnly(db);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static String row(int threads, String metric, long value, String unit, String includes) {
        return String.format(Locale.ROOT, "%d,%s,%d,%s,\"%s\"", threads, metric, value, unit, includes);
    }
}
