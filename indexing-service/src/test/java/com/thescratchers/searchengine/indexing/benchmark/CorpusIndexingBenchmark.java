package com.thescratchers.searchengine.indexing.benchmark;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.common.TextTokenizer;
import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.IngestionLogFixture;
import com.thescratchers.searchengine.indexing.metadata.HeaderParser;
import com.thescratchers.searchengine.indexing.metadata.ParsedHeader;
import com.thescratchers.searchengine.indexing.service.IndexingService;
import com.thescratchers.searchengine.indexing.service.RebuildInProgressException;
import com.thescratchers.searchengine.indexing.service.RebuildResult;
import com.thescratchers.searchengine.indexing.source.IngestionLogReader;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks of indexing the whole fixed corpus (20 books) with 1, 2 and 4 threads, which play the
 * role of the {@code INDEXING_WORKERS} consumer threads: every thread takes the next book from a
 * shared queue, as competing consumers take messages.
 *
 * <p>Following correction 4 of Stage 1, construction, persistence and incremental update are
 * measured separately:
 * <ul>
 *   <li>{@link #analyseCorpus}: construction only. Reads, parses and tokenizes every book and
 *       counts its terms, in memory.</li>
 *   <li>{@link #persistCorpus}: persistence only. Stores precomputed books and term frequencies in
 *       an empty datamart, one SQLite transaction per book.</li>
 *   <li>{@link #buildCorpus}: both, as the service does it ({@link BookIndexer#index}), from an
 *       empty datamart.</li>
 *   <li>{@link #updateBatch}: incremental update. Re-indexes an identical batch (the corpus) into a
 *       datamart that already holds it, so every book replaces its previous version.</li>
 *   <li>{@link #rebuild}: the complete rebuild of {@code POST /index/rebuild}
 *       ({@link IndexingService#rebuild}) over a full datamart, which reads the ingestion log,
 *       re-indexes every available book sequentially and removes the books that are no longer
 *       available.</li>
 * </ul>
 *
 * <p>Each operation processes the whole corpus once, so the mode is single-shot time: 5 warm-up and
 * 10 measured operations per configuration, with an explicit limit of 2 minutes per operation. Logging
 * is reduced to warnings in the benchmark JVM, so that writing a line per book is not measured.
 * Datamarts live in temporary directories; nothing is written under {@code data/}.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Timeout(time = 2, timeUnit = TimeUnit.MINUTES)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx2G", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"})
public class CorpusIndexingBenchmark {

    /** Metadata and term frequencies of a book, computed in memory. */
    record AnalysedBook(BookRecord record, Map<String, Integer> termFrequencies) {
    }

    /** The corpus ids, and every book analysed once per trial for {@link #persistCorpus}. */
    @State(Scope.Benchmark)
    public static class CorpusState {

        List<Integer> bookIds;
        List<AnalysedBook> analysed;

        /** Loads the corpus list and analyses every book. */
        @Setup(Level.Trial)
        public void load() throws IOException {
            bookIds = Corpus.bookIds();
            analysed = new ArrayList<>();
            for (int bookId : bookIds) {
                analysed.add(analyse(bookId));
            }
        }
    }

    /** Pool of {@code threads} worker threads, created once per trial so that its start-up is not measured. */
    @State(Scope.Benchmark)
    public static class Workers {

        /** Number of worker threads, as {@code INDEXING_WORKERS}. */
        @Param({"1", "2", "4"})
        public int threads;

        ExecutorService pool;

        /** Starts the worker threads. */
        @Setup(Level.Trial)
        public void start() {
            pool = Executors.newFixedThreadPool(threads);
        }

        /** Stops the worker threads. */
        @TearDown(Level.Trial)
        public void stop() {
            pool.shutdownNow();
        }

        /** Runs every task on the pool and waits for all of them, rethrowing the first failure. */
        <T> List<T> runAll(List<Callable<T>> tasks) throws InterruptedException, ExecutionException {
            List<T> results = new ArrayList<>();
            for (Future<T> future : pool.invokeAll(tasks)) {
                results.add(future.get());
            }
            return results;
        }
    }

    /** An empty datamart, created before every operation and deleted after it. */
    @State(Scope.Benchmark)
    public static class EmptyDatamart {

        TempDirectory dir;
        DatamartStore store;
        BookIndexer indexer;

        /** Creates the temporary directory and the schema. */
        @Setup(Level.Invocation)
        public void create() throws IOException, SQLException {
            dir = TempDirectory.create();
            store = new DatamartStore(dir.datamart());
            indexer = new BookIndexer(store);
        }

        /** Deletes the datamart. */
        @TearDown(Level.Invocation)
        public void delete() throws IOException {
            dir.close();
        }
    }

    /**
     * A datamart that already holds the whole corpus, and an ingestion log that lists the corpus as
     * available, as the Ingestion Service leaves it. Re-indexing leaves the same content, so it is
     * built once per trial.
     */
    @State(Scope.Benchmark)
    public static class IndexedDatamart {

        TempDirectory dir;
        BookIndexer indexer;
        IndexingService service;

        /** Indexes the corpus and writes the ingestion log. */
        @Setup(Level.Trial)
        public void create() throws IOException, SQLException {
            dir = TempDirectory.create();
            DatamartStore store = new DatamartStore(dir.datamart());
            indexer = new BookIndexer(store);
            IngestionLogFixture ingestionLog = new IngestionLogFixture(dir.path().resolve("ingestion.db"));
            for (int bookId : Corpus.bookIds()) {
                indexer.index(bookId, Corpus.header(bookId), Corpus.body(bookId));
                ingestionLog.available(bookId);
            }
            service = new IndexingService(new IngestionLogReader(dir.path().resolve("ingestion.db")),
                    indexer, store, result -> { });
        }

        /** Deletes the datamart and the ingestion log. */
        @TearDown(Level.Trial)
        public void delete() throws IOException {
            dir.close();
        }
    }

    /** Construction only: analyses every corpus book in memory. */
    @Benchmark
    public List<AnalysedBook> analyseCorpus(CorpusState corpus, Workers workers) throws Exception {
        List<Callable<AnalysedBook>> tasks = new ArrayList<>();
        for (int bookId : corpus.bookIds) {
            tasks.add(() -> analyse(bookId));
        }
        return workers.runAll(tasks);
    }

    /** Persistence only: stores every analysed book in an empty datamart. */
    @Benchmark
    public List<Integer> persistCorpus(CorpusState corpus, Workers workers, EmptyDatamart datamart) throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (AnalysedBook book : corpus.analysed) {
            tasks.add(() -> {
                datamart.store.indexBook(book.record(), book.termFrequencies());
                return book.record().bookId();
            });
        }
        return workers.runAll(tasks);
    }

    /** Construction and persistence: indexes the corpus from its files into an empty datamart. */
    @Benchmark
    public List<BookIndexer.IndexResult> buildCorpus(CorpusState corpus, Workers workers, EmptyDatamart datamart)
            throws Exception {
        return workers.runAll(indexTasks(corpus.bookIds, datamart.indexer));
    }

    /** Incremental update: re-indexes an identical batch into a datamart that already holds it. */
    @Benchmark
    public List<BookIndexer.IndexResult> updateBatch(CorpusState corpus, Workers workers, IndexedDatamart datamart)
            throws Exception {
        return workers.runAll(indexTasks(corpus.bookIds, datamart.indexer));
    }

    /** Complete rebuild through the service, as {@code POST /index/rebuild} runs it. */
    @Benchmark
    public RebuildResult rebuild(IndexedDatamart datamart) throws RebuildInProgressException, SQLException {
        RebuildResult result = datamart.service.rebuild();
        if (!result.failedBooks().isEmpty()) {
            throw new IllegalStateException("rebuild could not index books " + result.failedBooks());
        }
        return result;
    }

    private static List<Callable<BookIndexer.IndexResult>> indexTasks(List<Integer> bookIds, BookIndexer indexer) {
        List<Callable<BookIndexer.IndexResult>> tasks = new ArrayList<>();
        for (int bookId : bookIds) {
            tasks.add(() -> indexer.index(bookId, Corpus.header(bookId), Corpus.body(bookId)));
        }
        return tasks;
    }

    /** Reads, parses and tokenizes one book and counts its terms, as {@link BookIndexer} does before storing it. */
    static AnalysedBook analyse(int bookId) throws IOException {
        ParsedHeader header = HeaderParser.parse(Files.readString(Corpus.header(bookId), StandardCharsets.UTF_8));
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : TextTokenizer.tokenize(Files.readString(Corpus.body(bookId), StandardCharsets.UTF_8))) {
            frequencies.merge(token, 1, Integer::sum);
        }
        BookRecord record = new BookRecord(bookId, header.title(), header.author(), header.language(), header.year());
        return new AnalysedBook(record, frequencies);
    }
}
