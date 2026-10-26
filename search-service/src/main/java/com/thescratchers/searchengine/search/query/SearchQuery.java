package com.thescratchers.searchengine.search.query;

import com.thescratchers.searchengine.common.TextTokenizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A validated and normalised {@code GET /search} request. The rules are those of
 * {@code docs/openapi.yaml}; {@link #parse} is the only place where they are enforced.
 *
 * @param query    the {@code q} parameter as received, trimmed
 * @param terms    distinct terms of {@code q} produced by {@link TextTokenizer}, in order of appearance
 * @param author   case-insensitive author substring, or {@code null} when not filtered
 * @param language ISO 639-1 code in lower case, or {@code null} when not filtered
 * @param year     Project Gutenberg release year, or {@code null} when not filtered
 * @param offset   number of matching books to skip
 * @param limit    maximum number of books in the page
 */
public record SearchQuery(String query, List<String> terms, String author, String language, Integer year,
                          int offset, int limit) {

    /** Maximum length of {@code q}, in characters. */
    public static final int MAX_QUERY_LENGTH = 200;
    /** Maximum length of the {@code author} filter, in characters. */
    public static final int MAX_AUTHOR_LENGTH = 100;
    /** Page size used when {@code limit} is absent. */
    public static final int DEFAULT_LIMIT = 20;
    /** Largest page size a client may request. */
    public static final int MAX_LIMIT = 100;

    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[A-Za-z]{2}");
    private static final Pattern YEAR_PATTERN = Pattern.compile("[1-9][0-9]{3}");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("[0-9]{1,9}");

    public SearchQuery {
        terms = List.copyOf(terms);
    }

    /**
     * Validates the raw query parameters of a search request. Absent parameters are {@code null};
     * blank filters, offsets and limits are treated as absent.
     *
     * @throws InvalidParameterException if a parameter breaks its rule
     */
    public static SearchQuery parse(String q, String author, String language, String year,
                                    String offset, String limit) {
        String query = q == null ? "" : q.strip();
        if (query.isEmpty()) {
            throw new InvalidParameterException("q is required");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new InvalidParameterException("q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(TextTokenizer.tokenize(query)));
        if (terms.isEmpty()) {
            throw new InvalidParameterException("q must contain at least one word");
        }
        return new SearchQuery(query, terms, parseAuthor(author), parseLanguage(language), parseYear(year),
                parseOffset(offset), parseLimit(limit));
    }

    /** The filters applied, after normalisation, in the shape of the {@code filters} response field. */
    public Map<String, Object> filters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (author != null) {
            filters.put("author", author);
        }
        if (language != null) {
            filters.put("language", language);
        }
        if (year != null) {
            filters.put("year", year);
        }
        return filters;
    }

    private static String parseAuthor(String raw) {
        String author = blankToNull(raw);
        if (author != null && author.length() > MAX_AUTHOR_LENGTH) {
            throw new InvalidParameterException("author must be at most " + MAX_AUTHOR_LENGTH + " characters");
        }
        return author;
    }

    private static String parseLanguage(String raw) {
        String language = blankToNull(raw);
        if (language == null) {
            return null;
        }
        if (!LANGUAGE_PATTERN.matcher(language).matches()) {
            throw new InvalidParameterException("language must be a two-letter ISO 639-1 code");
        }
        return language.toLowerCase(Locale.ROOT);
    }

    private static Integer parseYear(String raw) {
        String year = blankToNull(raw);
        if (year == null) {
            return null;
        }
        if (!YEAR_PATTERN.matcher(year).matches()) {
            throw new InvalidParameterException("year must be a four-digit number");
        }
        return Integer.parseInt(year);
    }

    private static int parseOffset(String raw) {
        String offset = blankToNull(raw);
        if (offset == null) {
            return 0;
        }
        if (!DIGITS_PATTERN.matcher(offset).matches()) {
            throw new InvalidParameterException("offset must be a non-negative integer below 1000000000");
        }
        return Integer.parseInt(offset);
    }

    private static int parseLimit(String raw) {
        String limit = blankToNull(raw);
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        int value = DIGITS_PATTERN.matcher(limit).matches() ? Integer.parseInt(limit) : -1;
        if (value < 1 || value > MAX_LIMIT) {
            throw new InvalidParameterException("limit must be between 1 and " + MAX_LIMIT);
        }
        return value;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }
}
