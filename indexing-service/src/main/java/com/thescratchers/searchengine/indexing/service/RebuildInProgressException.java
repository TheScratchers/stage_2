package com.thescratchers.searchengine.indexing.service;

/** Thrown when a rebuild is requested while this process is already running one. */
public final class RebuildInProgressException extends Exception {

    /** Creates the exception. */
    public RebuildInProgressException() {
        super("a rebuild is already running");
    }
}
