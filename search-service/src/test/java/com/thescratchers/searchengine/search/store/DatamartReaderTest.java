package com.thescratchers.searchengine.search.store;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.search.model.BookDetails;
import com.thescratchers.searchengine.search.model.FilterValues;
import com.thescratchers.searchengine.search.model.FilterValues.LanguageCount;
import com.thescratchers.searchengine.search.model.FilterValues.YearCount;
import com.thescratchers.searchengine.search.model.SearchHit;
import com.thescratchers.searchengine.search.model.SearchPage;
import com.thescratchers.searchengine.search.query.SearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expected results are worked out by hand from the table in {@link TestDatamart}. */
class DatamartReaderTest {

    @TempDir
    Path tempDir;

    private DatamartReader reader;

    @BeforeEach
    void createDatamart() throws SQLException {
        reader = new DatamartReader(TestDatamart.create(tempDir.resolve("datamart.db")));
    }

    @Test
    void ordersByScoreThenByBookId() throws Exception {
        SearchPage page = reader.search(query("adventure"));

        assertEquals(5, page.total());
        // Scores 4, 4, 2, 2, 1: ties are broken by ascending book_id.
        assertEquals(List.of(2, 4, 1, 3, 5), ids(page));
        assertEquals(List.of(4L, 4L, 2L, 2L, 1L), page.hits().stream().map(SearchHit::score).toList());
    }

    @Test
    void combinesTermsWithAndAndSumsTheirFrequencies() throws Exception {
        SearchPage page = reader.search(query("river adventure"));

        // Book 3 and 4 lack "river"; scores: 5 -> 9+1, 1 -> 5+2, 2 -> 1+4.
        assertEquals(List.of(5, 1, 2), ids(page));
        assertEquals(List.of(10L, 7L, 5L), page.hits().stream().map(SearchHit::score).toList());
    }

    @Test
    void repeatedTermsCountOnce() throws Exception {
        assertEquals(ids(reader.search(query("river"))), ids(reader.search(query("River river RIVER"))));
    }

    @Test
    void returnsFullMetadataOfEachHit() throws Exception {
        SearchPage page = reader.search(query("raft"));

        assertEquals(List.of(new SearchHit(new BookRecord(1, "Life on the River", "Mark Twain", "en", 2004), 3)),
                page.hits());
    }

    @Test
    void keepsNullMetadataAsNull() throws Exception {
        SearchHit hit = reader.search(query("rivière")).hits().get(0);
        assertNull(hit.book().author());

        SearchHit noLanguage = reader.search(query("moor", null, null, "2010")).hits().get(0);
        assertEquals(new BookRecord(6, "Untitled", "Anonymous", null, 2010), noLanguage.book());

        SearchHit noYear = reader.search(query("river", "verne", null, null)).hits().get(0);
        assertNull(noYear.book().year());
    }

    @Test
    void validQueryWithoutMatchesReturnsAnEmptyPage() throws Exception {
        SearchPage page = reader.search(query("zxqv"));

        assertEquals(0, page.total());
        assertTrue(page.hits().isEmpty());
    }

    @Test
    void authorIsACaseInsensitiveSubstring() throws Exception {
        assertEquals(List.of(2, 1), ids(reader.search(query("adventure", "TWAIN", null, null))));
        assertEquals(List.of(3), ids(reader.search(query("adventure", "brontë", null, null))));
        // Non-ASCII letters are folded too, unlike SQLite's lower().
        assertEquals(List.of(3), ids(reader.search(query("adventure", "BRONTË", null, null))));
    }

    @Test
    void booksWithoutAuthorNeverMatchAnAuthorFilter() throws Exception {
        assertEquals(List.of(), ids(reader.search(query("rivière", "a", null, null))));
    }

    @Test
    void filtersByLanguage() throws Exception {
        assertEquals(List.of(4, 5), ids(reader.search(query("adventure", null, "FR", null))));
        assertEquals(0, reader.search(query("adventure", null, "de", null)).total());
    }

    @Test
    void filtersByYear() throws Exception {
        assertEquals(List.of(4, 3), ids(reader.search(query("adventure", null, null, "1998"))));
    }

    @Test
    void combinesFilters() throws Exception {
        assertEquals(List.of(4), ids(reader.search(query("adventure", null, "fr", "1998"))));
        assertEquals(List.of(2, 1), ids(reader.search(query("adventure", "twain", "en", "2004"))));
        assertEquals(List.of(), ids(reader.search(query("adventure", "twain", "fr", null))));
    }

    @Test
    void paginatesWithoutChangingTheTotal() throws Exception {
        SearchPage first = reader.search(page("adventure", "0", "2"));
        SearchPage second = reader.search(page("adventure", "2", "2"));
        SearchPage last = reader.search(page("adventure", "4", "2"));

        assertEquals(List.of(2, 4), ids(first));
        assertEquals(List.of(1, 3), ids(second));
        assertEquals(List.of(5), ids(last));
        assertEquals(List.of(5, 5, 5), List.of(first.total(), second.total(), last.total()));
    }

    @Test
    void offsetBeyondTheTotalReturnsAnEmptyPageWithTheTotal() throws Exception {
        SearchPage page = reader.search(page("adventure", "5", "20"));

        assertEquals(5, page.total());
        assertTrue(page.hits().isEmpty());
    }

    @Test
    void findsAnIndexedBook() throws Exception {
        assertEquals(Optional.of(new BookDetails(
                        new BookRecord(3, "Moorland Stories", "Charlotte Brontë", "en", 1998), "2026-10-25T10:00:03Z")),
                reader.findBook(3));
        assertEquals(Optional.empty(), reader.findBook(999));
    }

    @Test
    void listsFilterValuesWithBookCounts() throws Exception {
        FilterValues values = reader.filterValues();

        assertEquals(6, values.totalBooks());
        assertEquals(List.of(new LanguageCount("en", 3), new LanguageCount("fr", 2)), values.languages());
        assertEquals(List.of(new YearCount(1998, 2), new YearCount(2004, 2), new YearCount(2010, 1)), values.years());
        assertEquals(TestDatamart.LAST_UPDATE, values.indexUpdatedAt());
    }

    @Test
    void emptyIndexReturnsNoResultsAndNoFilterValues() throws Exception {
        DatamartReader empty = new DatamartReader(TestDatamart.createEmpty(tempDir.resolve("empty.db")));

        assertEquals(0, empty.search(query("adventure")).total());
        assertEquals(Optional.empty(), empty.findBook(1));
        assertEquals(new FilterValues(0, List.of(), List.of(), null), empty.filterValues());
    }

    @Test
    void missingDatamartIsUnavailableAndIsNotCreated() {
        Path missing = tempDir.resolve("data").resolve("datamarts").resolve("datamart.db");
        DatamartReader unavailable = new DatamartReader(missing);

        assertThrows(IndexUnavailableException.class, () -> unavailable.search(query("adventure")));
        assertThrows(IndexUnavailableException.class, () -> unavailable.findBook(1));
        assertThrows(IndexUnavailableException.class, unavailable::filterValues);
        assertFalse(Files.exists(missing.getParent()));
    }

    @Test
    void datamartWithoutSchemaIsUnavailable() throws Exception {
        Path noSchema = tempDir.resolve("no-schema.db");
        Files.createFile(noSchema);

        assertThrows(IndexUnavailableException.class, () -> new DatamartReader(noSchema).search(query("adventure")));
    }

    private static SearchQuery query(String q) {
        return query(q, null, null, null);
    }

    private static SearchQuery query(String q, String author, String language, String year) {
        return SearchQuery.parse(q, author, language, year, null, null);
    }

    private static SearchQuery page(String q, String offset, String limit) {
        return SearchQuery.parse(q, null, null, null, offset, limit);
    }

    private static List<Integer> ids(SearchPage page) {
        return page.hits().stream().map(hit -> hit.book().bookId()).toList();
    }
}
