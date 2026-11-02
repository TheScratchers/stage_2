package com.thescratchers.searchengine.indexing.events;

import com.thescratchers.searchengine.indexing.BookIndexer.IndexResult;

import java.time.Instant;

/**
 * Payload of the {@code BookIndexed} event that the Indexing Service publishes on the
 * {@code books.indexed} topic after committing a book (see {@code docs/contracts.md}, section 4).
 *
 * @param event     constant {@code "BookIndexed"}
 * @param version   payload version (1)
 * @param bookId    the book that has just been committed to the datamart
 * @param terms     number of distinct terms indexed for the book
 * @param indexedAt time after the commit, ISO-8601 UTC
 */
public record BookIndexedEvent(String event, int version, int bookId, int terms, String indexedAt) {

    /** Value of {@link #event()} for this event type. */
    public static final String NAME = "BookIndexed";

    /** Payload version published by this service. */
    public static final int VERSION = 1;

    /** Builds the event for a book that has just been committed. */
    public static BookIndexedEvent of(IndexResult result) {
        return new BookIndexedEvent(NAME, VERSION, result.bookId(), result.distinctTerms(), Instant.now().toString());
    }
}
