package com.thescratchers.searchengine.indexing.service;

import com.thescratchers.searchengine.indexing.BookIndexer;
import com.thescratchers.searchengine.indexing.BookIndexer.IndexResult;
import com.thescratchers.searchengine.indexing.source.BookFiles;
import com.thescratchers.searchengine.indexing.source.BookSource;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Administrative operations of the Indexing Service: re-index one book, rebuild the whole index
 * and report its status.
 *
 * <p>Books are located through a {@link BookSource}; their content is indexed by
 * {@link BookIndexer}, one SQLite transaction per book, so the Search Service always sees each book
 * either in its previous or in its new version. Thread-safe: the only mutable state is the flag that
 * prevents two rebuilds from running at the same time in this process. It does not coordinate
 * replicas, but concurrent rebuilds in different processes are harmless because indexing a book is
 * idempotent.
 */
public final class IndexingService {

    private static final Logger LOG = LoggerFactory.getLogger(IndexingService.class);

    private final BookSource source;
    private final BookIndexer indexer;
    private final DatamartStore store;
    private final IndexListener listener;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /**
     * Creates the service over the given book source, indexer and datamart.
     *
     * @param listener notified after each book is committed ({@link IndexListener#NONE} for none)
     */
    public IndexingService(BookSource source, BookIndexer indexer, DatamartStore store, IndexListener listener) {
        this.source = source;
        this.indexer = indexer;
        this.store = store;
        this.listener = listener;
    }

    /**
     * Indexes (or re-indexes) one available book, replacing its previous version.
     *
     * @throws BookNotAvailableException if the Ingestion Service has not made the book available
     * @throws IOException               if its files cannot be read
     * @throws SQLException              if the source or the datamart cannot be accessed
     */
    public IndexResult update(int bookId) throws BookNotAvailableException, IOException, SQLException {
        BookFiles files = source.find(bookId).orElseThrow(() -> new BookNotAvailableException(bookId));
        IndexResult result = indexer.index(files.bookId(), files.headerFile(), files.bodyFile());
        listener.onIndexed(result);
        return result;
    }

    /**
     * Rebuilds the index from every book available in the datalake: each one is re-indexed in its
     * own transaction, and then indexed books that are no longer available are removed. The final
     * state equals an index built from scratch, but Search never sees an empty index meanwhile. A
     * book that fails is logged, reported and keeps its previous version; the others go on.
     *
     * @throws RebuildInProgressException if this process is already rebuilding
     * @throws SQLException               if the list of books cannot be read or a book cannot be removed
     */
    public RebuildResult rebuild() throws RebuildInProgressException, SQLException {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new RebuildInProgressException();
        }
        try {
            long start = System.nanoTime();
            List<BookFiles> available = source.listAvailable();
            Set<Integer> availableIds = new HashSet<>();
            List<Integer> failed = new ArrayList<>();
            for (BookFiles files : available) {
                availableIds.add(files.bookId());
                try {
                    listener.onIndexed(indexer.index(files.bookId(), files.headerFile(), files.bodyFile()));
                } catch (IOException | SQLException e) {
                    LOG.warn("Rebuild could not index book {}: {}", files.bookId(), e.getMessage());
                    failed.add(files.bookId());
                }
            }
            int removed = 0;
            for (int bookId : store.bookIds()) {
                if (!availableIds.contains(bookId) && store.deleteBook(bookId)) {
                    removed++;
                }
            }
            RebuildResult result = new RebuildResult(available.size() - failed.size(), List.copyOf(failed),
                    removed, Duration.ofNanos(System.nanoTime() - start));
            LOG.info("Rebuild finished: {} books indexed, {} failed, {} removed in {} ms", result.booksProcessed(),
                    failed.size(), removed, result.elapsed().toMillis());
            return result;
        } finally {
            rebuilding.set(false);
        }
    }

    /**
     * Returns the number of indexed books, the time of the last change and the size of the datamart.
     *
     * @throws SQLException if the datamart cannot be read
     * @throws IOException  if its size cannot be read
     */
    public IndexStatus status() throws SQLException, IOException {
        return new IndexStatus(store.countBooks(), store.lastUpdate().orElse(null), store.sizeBytes());
    }
}
