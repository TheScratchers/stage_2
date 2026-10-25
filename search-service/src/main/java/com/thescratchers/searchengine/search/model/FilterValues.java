package com.thescratchers.searchengine.search.model;

import java.util.List;

/**
 * Filter values present in the index, as returned by {@code GET /search/filters}.
 *
 * @param totalBooks     number of indexed books
 * @param languages      languages with at least one book, ordered by code
 * @param years          release years with at least one book, ascending
 * @param indexUpdatedAt last update of the index (ISO-8601 UTC), or {@code null} while it is empty
 */
public record FilterValues(int totalBooks, List<LanguageCount> languages, List<YearCount> years,
                           String indexUpdatedAt) {

    public FilterValues {
        languages = List.copyOf(languages);
        years = List.copyOf(years);
    }

    /** Number of indexed books in one language. */
    public record LanguageCount(String code, int books) {
    }

    /** Number of indexed books released in one year. */
    public record YearCount(int year, int books) {
    }
}
