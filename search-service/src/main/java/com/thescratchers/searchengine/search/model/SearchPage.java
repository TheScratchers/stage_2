package com.thescratchers.searchengine.search.model;

import java.util.List;

/**
 * One page of search results.
 *
 * @param total number of books that match the query and filters, across all pages
 * @param hits  the requested page, in result order; empty when there are no matches or the
 *              offset is beyond {@code total}
 */
public record SearchPage(int total, List<SearchHit> hits) {

    public SearchPage {
        hits = List.copyOf(hits);
    }
}
