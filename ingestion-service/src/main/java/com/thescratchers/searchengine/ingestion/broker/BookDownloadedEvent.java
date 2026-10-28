package com.thescratchers.searchengine.ingestion.broker;

import java.time.Instant;

/**
 * Event published to ActiveMQ queue {@code books.downloaded} when a book has been successfully stored
 * in the datalake and recorded in {@code ingestion_log}.
 *
 * @param event       constant {@code "BookDownloaded"}
 * @param version     event schema version (1)
 * @param book_id     Project Gutenberg book id
 * @param header_path path to {@code <id>.header.txt} in the datalake
 * @param body_path   path to {@code <id>.body.txt} in the datalake
 * @param ingested_at ISO-8601 UTC timestamp of ingestion
 */
public record BookDownloadedEvent(
        String event,
        int version,
        int book_id,
        String header_path,
        String body_path,
        String ingested_at
) {
    public static final String EVENT_NAME = "BookDownloaded";
    public static final int CURRENT_VERSION = 1;

    public static BookDownloadedEvent of(int bookId, String headerPath, String bodyPath, Instant ingestedAt) {
        return new BookDownloadedEvent(EVENT_NAME, CURRENT_VERSION, bookId, headerPath, bodyPath, ingestedAt.toString());
    }
}
