package com.thescratchers.searchengine.ingestion.gutenberg;

/** A book could not be obtained from Project Gutenberg; {@link #reason()} says why. */
public class DownloadException extends Exception {

    /** Cause of the failure, used to choose the error code returned by the API. */
    public enum Reason {
        /** Gutenberg answered 404: the book id does not exist. */
        NOT_FOUND,
        /** Gutenberg failed, timed out or could not be reached. Retrying later may succeed. */
        UPSTREAM_ERROR,
        /** The text has no recognisable START/END markers, so header and body cannot be split. */
        INVALID_FORMAT
    }

    private final int bookId;
    private final Reason reason;

    public DownloadException(int bookId, Reason reason, String message) {
        this(bookId, reason, message, null);
    }

    public DownloadException(int bookId, Reason reason, String message, Throwable cause) {
        super("Book " + bookId + ": " + message, cause);
        this.bookId = bookId;
        this.reason = reason;
    }

    public int bookId() {
        return bookId;
    }

    public Reason reason() {
        return reason;
    }
}
