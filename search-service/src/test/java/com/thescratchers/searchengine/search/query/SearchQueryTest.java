package com.thescratchers.searchengine.search.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchQueryTest {

    @Test
    void appliesDefaultsWhenOnlyTheQueryIsGiven() {
        SearchQuery query = SearchQuery.parse("monster", null, null, null, null, null);

        assertEquals(new SearchQuery("monster", List.of("monster"), null, null, null, 0, 20), query);
        assertEquals(Map.of(), query.filters());
    }

    @Test
    void tokenizesTheQueryIntoDistinctTermsInOrder() {
        SearchQuery query = SearchQuery.parse("  The whale, THE sea & the Whale!  ", null, null, null, null, null);

        assertEquals("The whale, THE sea & the Whale!", query.query());
        assertEquals(List.of("the", "whale", "sea"), query.terms());
    }

    @Test
    void keepsAccentedWordsWhole() {
        assertEquals(List.of("rivière"), SearchQuery.parse("Rivière", null, null, null, null, null).terms());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void queryIsRequired(String q) {
        assertInvalid("q is required", () -> SearchQuery.parse(q, null, null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"??", "1999", "-- 42 --"})
    void queryWithoutWordsIsRejected(String q) {
        assertInvalid("q must contain at least one word", () -> SearchQuery.parse(q, null, null, null, null, null));
    }

    @Test
    void queryLengthIsLimited() {
        String atLimit = "a".repeat(SearchQuery.MAX_QUERY_LENGTH);
        assertEquals(atLimit, SearchQuery.parse(atLimit, null, null, null, null, null).query());

        assertInvalid("q must be at most 200 characters",
                () -> SearchQuery.parse(atLimit + "a", null, null, null, null, null));
    }

    @Test
    void normalisesFiltersAndReportsThemInOrder() {
        SearchQuery query = SearchQuery.parse("darcy", "  Jane Austen ", "EN", "1998", null, null);

        assertEquals("Jane Austen", query.author());
        assertEquals("en", query.language());
        assertEquals(1998, query.year());
        assertEquals(List.of("author", "language", "year"), List.copyOf(query.filters().keySet()));
        assertEquals(Map.of("author", "Jane Austen", "language", "en", "year", 1998), query.filters());
    }

    @Test
    void blankFiltersAreTreatedAsAbsent() {
        SearchQuery query = SearchQuery.parse("darcy", "", " ", "", "", " ");

        assertNull(query.author());
        assertNull(query.language());
        assertNull(query.year());
        assertEquals(0, query.offset());
        assertEquals(20, query.limit());
    }

    @Test
    void authorLengthIsLimited() {
        String atLimit = "a".repeat(SearchQuery.MAX_AUTHOR_LENGTH);
        assertEquals(atLimit, SearchQuery.parse("x", atLimit, null, null, null, null).author());

        assertInvalid("author must be at most 100 characters",
                () -> SearchQuery.parse("x", atLimit + "a", null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"e", "eng", "e1", "en-GB", "ñe"})
    void languageMustBeATwoLetterCode(String language) {
        assertInvalid("language must be a two-letter ISO 639-1 code",
                () -> SearchQuery.parse("x", null, language, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"98", "0999", "19999", "abcd", "-1998", "1998.0"})
    void yearMustHaveFourDigits(String year) {
        assertInvalid("year must be a four-digit number", () -> SearchQuery.parse("x", null, null, year, null, null));
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "40, 1", "0, 100", "999999999, 20"})
    void acceptsOffsetsAndLimitsInRange(int offset, int limit) {
        SearchQuery query = SearchQuery.parse("x", null, null, null, String.valueOf(offset), String.valueOf(limit));

        assertEquals(offset, query.offset());
        assertEquals(limit, query.limit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc", "1.5", "1000000000", "99999999999999999999"})
    void rejectsInvalidOffsets(String offset) {
        assertInvalid("offset must be a non-negative integer below 1000000000",
                () -> SearchQuery.parse("x", null, null, null, offset, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "500", "-5", "abc", "99999999999999999999"})
    void rejectsLimitsOutOfRange(String limit) {
        assertInvalid("limit must be between 1 and 100", () -> SearchQuery.parse("x", null, null, null, null, limit));
    }

    private static void assertInvalid(String message, Runnable parse) {
        InvalidParameterException e = assertThrows(InvalidParameterException.class, parse::run);
        assertEquals(message, e.getMessage());
    }
}
