package com.thescratchers.searchengine.indexing.events;

/**
 * Payload of the {@code BookDownloaded} event that the Ingestion Service publishes on the
 * {@code books.downloaded} queue (see {@code docs/contracts.md}, section 4).
 *
 * @param event      constant {@code "BookDownloaded"}
 * @param version    payload version (1)
 * @param bookId     the book whose files are complete in the datalake
 * @param headerPath header file, to be used as published
 * @param bodyPath   body file, to be used as published
 * @param ingestedAt ingestion time, ISO-8601 UTC
 */
public record BookDownloadedEvent(String event, int version, int bookId, String headerPath, String bodyPath,
                                  String ingestedAt) {

    /** Value of {@link #event()} for this event type. */
    public static final String NAME = "BookDownloaded";

    /** Payload version understood by this consumer. */
    public static final int VERSION = 1;

    /**
     * Checks that the event can be processed.
     *
     * @throws IllegalArgumentException if the name or version is unexpected, the id is not positive
     *                                  or a path is missing
     */
    public void validate() {
        if (!NAME.equals(event) || version != VERSION) {
            throw new IllegalArgumentException("not a BookDownloaded v" + VERSION + " event: " + event + " v" + version);
        }
        if (bookId <= 0 || headerPath == null || headerPath.isBlank() || bodyPath == null || bodyPath.isBlank()) {
            throw new IllegalArgumentException("BookDownloaded event without a valid book_id or paths");
        }
    }
}
