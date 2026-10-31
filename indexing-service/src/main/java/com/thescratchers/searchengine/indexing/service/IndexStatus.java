package com.thescratchers.searchengine.indexing.service;

/**
 * Snapshot of the index.
 *
 * @param booksIndexed number of books in the datamart
 * @param lastUpdate   time of the last committed change (ISO-8601 UTC), or {@code null} if none
 * @param sizeBytes    bytes of the datamart on disk (database file plus write-ahead log)
 */
public record IndexStatus(int booksIndexed, String lastUpdate, long sizeBytes) {
}
