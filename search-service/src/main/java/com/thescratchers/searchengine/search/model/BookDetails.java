package com.thescratchers.searchengine.search.model;

import com.thescratchers.searchengine.common.BookRecord;

/**
 * Metadata of one indexed book, as returned by {@code GET /books/{book_id}}.
 *
 * @param book      metadata of the book
 * @param indexedAt when the book was last indexed, ISO-8601 UTC
 */
public record BookDetails(BookRecord book, String indexedAt) {
}
