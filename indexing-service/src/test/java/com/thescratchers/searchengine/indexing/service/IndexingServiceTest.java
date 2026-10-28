package com.thescratchers.searchengine.indexing.service;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.IngestionLogFixture;
import com.thescratchers.searchengine.indexing.source.BookFiles;
import com.thescratchers.searchengine.indexing.source.BookSource;
import com.thescratchers.searchengine.indexing.source.IngestionLogReader;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingServiceTest {

    @TempDir
    Path tempDir;

    private IngestionLogFixture log;
    private DatamartStore store;
    private IndexingService service;

    @BeforeEach
    void setUp() throws Exception {
        Path ingestionDb = tempDir.resolve("ingestion.db");
        log = new IngestionLogFixture(ingestionDb);
        store = new DatamartStore(tempDir.resolve("datamart.db"));
        service = new IndexingService(new IngestionLogReader(ingestionDb), new BookIndexer(store), store, IndexListener.NONE);
    }

    @Test
    void updateIndexesAnAvailableBook() throws Exception {
        log.available(1342);

        BookIndexer.IndexResult result = service.update(1342);

        assertEquals(1342, result.bookId());
        assertTrue(store.booksForTerm("darcy").contains(1342));
    }

    @Test
    void updateOfABookThatIsNotAvailableFails() throws Exception {
        log.failed(5);

        assertThrows(BookNotAvailableException.class, () -> service.update(5));
        assertThrows(BookNotAvailableException.class, () -> service.update(999));
        assertEquals(0, store.countBooks());
    }

    @Test
    void rebuildIndexesEveryAvailableBookAndRemovesTheOthers() throws Exception {
        log.available(1342).available(11).failed(5);
        store.indexBook(new BookRecord(84, "Frankenstein", null, "en", 1993), Map.of("monster", 1));

        RebuildResult result = service.rebuild();

        assertEquals(2, result.booksProcessed());
        assertEquals(List.of(), result.failedBooks());
        assertEquals(1, result.booksRemoved());
        assertEquals(List.of(11, 1342), store.bookIds());
    }

    @Test
    void rebuildIsIdempotent() throws Exception {
        log.available(1342).available(11);
        service.rebuild();
        int postings = store.countPostings(1342);

        RebuildResult again = service.rebuild();

        assertEquals(2, again.booksProcessed());
        assertEquals(0, again.booksRemoved());
        assertEquals(2, store.countBooks());
        assertEquals(postings, store.countPostings(1342));
    }

    @Test
    void rebuildReportsBooksWhoseFilesAreMissingAndGoesOn() throws Exception {
        log.available(1342).available(7, tempDir.resolve("7.header.txt"), tempDir.resolve("7.body.txt"));

        RebuildResult result = service.rebuild();

        assertEquals(1, result.booksProcessed());
        assertEquals(List.of(7), result.failedBooks());
        assertEquals(List.of(1342), store.bookIds());
    }

    @Test
    void aSecondRebuildIsRejectedWhileOneIsRunning() throws Exception {
        CountDownLatch listing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BookSource slowSource = new BookSource() {
            @Override
            public Optional<BookFiles> find(int bookId) {
                return Optional.empty();
            }

            @Override
            public List<BookFiles> listAvailable() {
                listing.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        IndexingService slow = new IndexingService(slowSource, new BookIndexer(store), store, IndexListener.NONE);

        CompletableFuture<RebuildResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return slow.rebuild();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(listing.await(5, TimeUnit.SECONDS));

        assertThrows(RebuildInProgressException.class, slow::rebuild);

        release.countDown();
        assertNotNull(first.get(5, TimeUnit.SECONDS));
        assertNotNull(slow.rebuild());
    }

    @Test
    void statusReportsBooksLastUpdateAndSize() throws Exception {
        IndexStatus empty = service.status();
        assertEquals(0, empty.booksIndexed());
        assertNull(empty.lastUpdate());

        log.available(1342);
        service.update(1342);
        IndexStatus status = service.status();

        assertEquals(1, status.booksIndexed());
        assertNotNull(status.lastUpdate());
        assertTrue(status.sizeBytes() > 0);
    }

    @Test
    void theListenerIsNotifiedOnlyForCommittedBooks() throws Exception {
        List<Integer> notified = new CopyOnWriteArrayList<>();
        IndexingService listening = new IndexingService(new IngestionLogReader(tempDir.resolve("ingestion.db")),
                new BookIndexer(store), store, result -> notified.add(result.bookId()));
        log.available(1342).available(11).available(7, tempDir.resolve("7.header.txt"), tempDir.resolve("7.body.txt"));

        listening.update(1342);
        assertThrows(BookNotAvailableException.class, () -> listening.update(999));
        listening.rebuild();

        assertEquals(List.of(1342, 11, 1342), notified);
    }
}
