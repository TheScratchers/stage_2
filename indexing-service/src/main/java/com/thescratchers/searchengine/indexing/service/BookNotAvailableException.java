package com.thescratchers.searchengine.indexing.service;

/** Thrown when a book is asked to be indexed but the Ingestion Service has not made it available. */
public final class BookNotAvailableException extends Exception {

    /** Creates the exception for {@code bookId}. */
    public BookNotAvailableException(int bookId) {
        super("book " + bookId + " is not available in the datalake");
    }
}
