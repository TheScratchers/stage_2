package com.thescratchers.searchengine.ingestion;

/**
 * Result of ingesting a single book into the datalake.
 *
 * @param bookId Project Gutenberg book id
 * @param status {@code "downloaded"} if freshly fetched, or {@code "already_available"} if idempotent
 * @param path   absolute datalake prefix path for the book files (without {@code .header.txt} or {@code .body.txt})
 */
public record IngestResult(int bookId, String status, String path) {
    public static final String STATUS_DOWNLOADED = "downloaded";
    public static final String STATUS_ALREADY_AVAILABLE = "already_available";
}

