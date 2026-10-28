package com.thescratchers.searchengine.ingestion.datalake;

import java.time.Instant;

/**
 * An entry in {@code datalake/ingestion.db} tracking whether a book is available or failed.
 *
 * @param bookId     Project Gutenberg id
 * @param status     {@code 'available'} or {@code 'failed'}
 * @param headerPath relative path to {@code <id>.header.txt} in the datalake, or {@code null} on failure
 * @param bodyPath   relative path to {@code <id>.body.txt} in the datalake, or {@code null} on failure
 * @param ingestedAt instant the record was created or updated
 */
public record IngestionRecord(
        int bookId,
        String status,
        String headerPath,
        String bodyPath,
        Instant ingestedAt
) {
    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_FAILED = "failed";

    public boolean isAvailable() {
        return STATUS_AVAILABLE.equalsIgnoreCase(status);
    }
}
