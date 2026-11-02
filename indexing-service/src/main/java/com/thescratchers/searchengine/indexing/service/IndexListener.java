package com.thescratchers.searchengine.indexing.service;

import com.thescratchers.searchengine.indexing.BookIndexer.IndexResult;

/**
 * Notified by {@link IndexingService} after a book has been committed to the datamart, for
 * example to publish {@code BookIndexed}. Implementations must be thread-safe and must not throw:
 * the book is already committed when they run.
 */
@FunctionalInterface
public interface IndexListener {

    /** Listener that does nothing, for deployments or tests without a broker. */
    IndexListener NONE = result -> { };

    /** Called after {@code result.bookId()} has been committed. */
    void onIndexed(IndexResult result);
}
