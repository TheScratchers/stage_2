package com.thescratchers.searchengine.indexing.events;

import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.IndexSnapshot;
import com.thescratchers.searchengine.indexing.IngestionLogFixture;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure and concurrency checks of the Indexing Service over the whole corpus (correction "Extra"
 * of the team guide, task M5).
 *
 * <p>Every test compares the datamart it produces, row by row, with a reference index built by
 * indexing each corpus book once, sequentially. The corpus is sent twice, so every book is
 * indexed at least twice and duplicates are always part of the workload.
 *
 * <ul>
 *   <li>A real Indexing process killed in the middle of the batch leaves only complete books in the
 *       datamart, and a restarted process finishes the batch with the reference index.</li>
 *   <li>1, 2 and 4 worker threads produce exactly the reference index.</li>
 *   <li>Two Indexing processes (replicas) writing the same datamart also produce it.</li>
 * </ul>
 */
class IndexingRecoveryTest {

    private static final long WAIT_MS = 120_000;
    private static final long POLL_MS = 50;
    private static final int TIMES_EACH_BOOK_IS_SENT = 2;

    private static List<Integer> corpus;
    private static IndexSnapshot reference;

    @TempDir
    Path tempDir;

    private TestBroker broker;
    private Path dataDir;
    private Path datamart;
    private DatamartStore store;
    private final List<IndexingProcess> processes = new ArrayList<>();
    private BookDownloadedConsumer consumer;

    @BeforeAll
    static void buildReference(@TempDir Path referenceDir) throws Exception {
        corpus = IngestionLogFixture.corpusIds();
        Path db = referenceDir.resolve("reference.db");
        BookIndexer indexer = new BookIndexer(new DatamartStore(db));
        for (int bookId : corpus) {
            indexer.index(bookId,
                    IngestionLogFixture.SAMPLES.resolve(bookId + ".header.txt"),
                    IngestionLogFixture.SAMPLES.resolve(bookId + ".body.txt"));
        }
        reference = IndexSnapshot.read(db);
        assertEquals(corpus, List.copyOf(reference.books().keySet()));
    }

    @BeforeEach
    void setUp() throws Exception {
        broker = new TestBroker(tempDir.resolve("broker"));
        dataDir = tempDir.resolve("data");
        datamart = dataDir.resolve("datamarts").resolve("datamart.db");
        store = new DatamartStore(datamart);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (IndexingProcess process : processes) {
            process.close();
        }
        if (consumer != null) {
            consumer.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void anIndexingProcessKilledMidBatchLeavesNoHalfIndexedBookAndTheBatchIsCompletedAfterARestart()
            throws Exception {
        sendCorpus();
        IndexingProcess first = startProcess(1, "first");
        waitUntil("the first process indexes a book", () -> store.countBooks() > 0);

        first.kill();

        long pending = broker.pendingDownloaded();
        assertTrue(pending > 0, "the batch finished before the process was killed, so nothing was tested");
        IndexSnapshot afterKill = IndexSnapshot.read(datamart);
        assertEquals(reference.restrictedTo(afterKill.books().keySet()), afterKill,
                "every book in the datamart after the crash must be complete, with no orphan postings");

        startProcess(2, "restarted");
        waitForTheBatch();

        assertEquals(reference, IndexSnapshot.read(datamart));
        assertEquals(0, broker.deadLetterCount());
    }

    @ParameterizedTest(name = "{0} worker(s)")
    @ValueSource(ints = {1, 2, 4})
    void anyNumberOfWorkersBuildsTheSameIndex(int workers) throws Exception {
        consumer = new BookDownloadedConsumer(broker.url(), new BookIndexer(store), workers);
        consumer.start();

        sendCorpus();
        waitForTheBatch();

        assertEquals(reference, IndexSnapshot.read(datamart));
        assertEquals(0, broker.deadLetterCount());
    }

    @Test
    void twoReplicasWritingTheSameDatamartBuildTheSameIndex() throws Exception {
        IndexingProcess one = startProcess(2, "replica-1");
        IndexingProcess two = startProcess(2, "replica-2");
        waitUntil("both replicas consume the queue", () -> broker.downloadedConsumers() == 4);

        sendCorpus();
        waitForTheBatch();

        assertEquals(reference, IndexSnapshot.read(datamart));
        assertEquals(0, broker.deadLetterCount());
        assertTrue(one.indexedBooks() > 0 && two.indexedBooks() > 0,
                "both replicas must have indexed books, but they indexed "
                        + one.indexedBooks() + " and " + two.indexedBooks());
    }

    private void sendCorpus() throws Exception {
        for (int i = 0; i < TIMES_EACH_BOOK_IS_SENT; i++) {
            for (int bookId : corpus) {
                broker.sendDownloaded(bookId);
            }
        }
    }

    /** Waits until every message has been acknowledged, i.e. committed in SQLite and in JMS. */
    private void waitForTheBatch() throws Exception {
        waitUntil("every BookDownloaded event is acknowledged", () -> broker.pendingDownloaded() == 0);
    }

    private IndexingProcess startProcess(int workers, String name) throws IOException {
        Files.createDirectories(dataDir);
        IndexingProcess process = new IndexingProcess(dataDir, broker.url(), workers, tempDir.resolve(name + ".log"));
        processes.add(process);
        return process;
    }

    /** Polls {@code condition}, failing early with its log if a live process has died. */
    private void waitUntil(String description, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!condition.call()) {
            for (IndexingProcess process : processes) {
                if (!process.wasKilled()) {
                    process.requireAlive();
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting until " + description);
            }
            Thread.sleep(POLL_MS);
        }
    }
}
