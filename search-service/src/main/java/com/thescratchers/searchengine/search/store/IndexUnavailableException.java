package com.thescratchers.searchengine.search.store;

/**
 * The datamart does not exist yet or has no schema, typically because the Indexing Service has not
 * created it. It is returned to the client as an {@code UNAVAILABLE} error (503); retrying later
 * may succeed.
 */
public class IndexUnavailableException extends Exception {

    public IndexUnavailableException(String message) {
        super(message);
    }

    public IndexUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
