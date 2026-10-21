package com.thescratchers.searchengine.indexing.metadata;

/**
 * Metadata extracted from a Project Gutenberg header by {@link HeaderParser}.
 *
 * @param title    title of the book, "Untitled" when the header has none
 * @param author   author of the book, or {@code null} when missing
 * @param language ISO 639-1 lowercase code (e.g. "en"), or {@code null} when missing or unknown
 * @param year     year of the Gutenberg "Release date", or {@code null} when missing
 */
public record ParsedHeader(String title, String author, String language, Integer year) {
}
