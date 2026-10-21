package com.thescratchers.searchengine.ingestion.gutenberg;

/**
 * A Gutenberg text split at its START/END markers.
 *
 * @param header everything before the START marker line (title, author, language, release date...)
 * @param body   the text between the START and END marker lines
 */
public record SplitBook(String header, String body) {
}
