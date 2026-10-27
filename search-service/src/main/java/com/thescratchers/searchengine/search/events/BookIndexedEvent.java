package com.thescratchers.searchengine.search.events;

/**
 * Payload of the {@code BookIndexed} event published by the Indexing Service on the
 * {@code books.indexed} topic (see {@code docs/contracts.md}, section 4).
 *
 * @param event     constant {@code "BookIndexed"}
 * @param version   payload version (1)
 * @param bookId    the book that has just been committed to the datamart
 * @param terms     number of distinct terms indexed for the book
 * @param indexedAt commit time, ISO-8601 UTC
 */
public record BookIndexedEvent(String event, int version, int bookId, int terms, String indexedAt) {

    /** Value of {@link #event()} for this event type. */
    public static final String NAME = "BookIndexed";
}
