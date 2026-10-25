package com.thescratchers.searchengine.search.model;

import com.thescratchers.searchengine.common.BookRecord;

/**
 * One book that matches a search.
 *
 * @param book  metadata of the book
 * @param score sum of the frequencies of the query terms in the book; results are ordered by it
 */
public record SearchHit(BookRecord book, long score) {
}
