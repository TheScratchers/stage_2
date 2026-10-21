package com.thescratchers.searchengine.common;

/**
 * Metadata of one indexed book, as stored in the {@code books} table of the datamart and returned
 * by the search API.
 *
 * @param bookId   Project Gutenberg id
 * @param title    title from the Gutenberg header ("Untitled" when missing)
 * @param author   author from the header, or {@code null} when missing
 * @param language ISO 639-1 lowercase code (e.g. "en"), or {@code null} when unknown
 * @param year     year of the Gutenberg "Release date", or {@code null} when missing
 */
public record BookRecord(int bookId, String title, String author, String language, Integer year) {
}
