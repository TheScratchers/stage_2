package com.thescratchers.searchengine.search.api;

import com.thescratchers.searchengine.search.model.BookDetails;
import com.thescratchers.searchengine.search.model.SearchHit;
import com.thescratchers.searchengine.search.model.SearchPage;
import com.thescratchers.searchengine.search.query.SearchQuery;

import java.util.List;
import java.util.Map;

/**
 * JSON bodies of the Search API, as specified in {@code docs/openapi.yaml}. Field names are
 * converted to {@code snake_case} by the serializer, and {@code null} values are written
 * explicitly because the specification lists them as present and nullable.
 */
final class ApiResponses {

    private ApiResponses() {
    }

    /** Body of {@code GET /search}. */
    record SearchResponse(String query, List<String> terms, Map<String, Object> filters, int total,
                          int offset, int limit, List<SearchResult> results) {

        static SearchResponse of(SearchQuery query, SearchPage page) {
            return new SearchResponse(query.query(), query.terms(), query.filters(), page.total(),
                    query.offset(), query.limit(), page.hits().stream().map(SearchResult::of).toList());
        }
    }

    /** One element of {@code results} in {@link SearchResponse}. */
    record SearchResult(int bookId, String title, String author, String language, Integer year, long score) {

        static SearchResult of(SearchHit hit) {
            return new SearchResult(hit.book().bookId(), hit.book().title(), hit.book().author(),
                    hit.book().language(), hit.book().year(), hit.score());
        }
    }

    /** Body of {@code GET /books/{book_id}}. */
    record BookResponse(int bookId, String title, String author, String language, Integer year, String indexedAt) {

        static BookResponse of(BookDetails details) {
            return new BookResponse(details.book().bookId(), details.book().title(), details.book().author(),
                    details.book().language(), details.book().year(), details.indexedAt());
        }
    }

    /** Common error envelope: {@code {"error": {"code": ..., "message": ...}}}. */
    record ErrorResponse(Error error) {

        static ErrorResponse of(String code, String message) {
            return new ErrorResponse(new Error(code, message));
        }
    }

    /** Content of {@link ErrorResponse}. */
    record Error(String code, String message) {
    }
}
