package com.thescratchers.searchengine.ingestion.broker;

import java.time.Instant;

/**
 * Event published to ActiveMQ queue {@code books.downloaded} when a book has been successfully stored
 * in the datalake and recorded in {@code ingestion_log}.
 *
 * @param event      constant {@code "BookDownloaded"}
 * @param version    event schema version (1)
 * @param bookId     Project Gutenberg book id
 * @param headerPath absolute path to {@code <id>.header.txt} in the datalake
 * @param bodyPath   absolute path to {@code <id>.body.txt} in the datalake
 * @param ingestedAt ISO-8601 UTC timestamp of ingestion
 */
public record BookDownloadedEvent(
        String event,
        int version,
        int bookId,
        String headerPath,
        String bodyPath,
        String ingestedAt
) {
    public static final String EVENT_NAME = "BookDownloaded";
    public static final int CURRENT_VERSION = 1;

    public static BookDownloadedEvent of(int bookId, String headerPath, String bodyPath, Instant ingestedAt) {
        return new BookDownloadedEvent(EVENT_NAME, CURRENT_VERSION, bookId, headerPath, bodyPath, ingestedAt.toString());
    }
}

