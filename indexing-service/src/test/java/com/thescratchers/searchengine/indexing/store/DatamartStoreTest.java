package com.thescratchers.searchengine.indexing.store;

import com.thescratchers.searchengine.common.BookRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatamartStoreTest {

    private static final BookRecord BOOK = new BookRecord(1342, "Pride and Prejudice", "Jane Austen", "en", 1998);

    @TempDir
    Path tempDir;

    @Test
    void createsTheDatabaseInsideAMissingDirectory() throws SQLException {
        Path dbFile = tempDir.resolve("data").resolve("datamarts").resolve("datamart.db");
        assertFalse(Files.exists(dbFile.getParent()));

        new DatamartStore(dbFile);

        assertTrue(Files.exists(dbFile));
    }

    @Test
    void usesWriteAheadLogging() throws SQLException {
        Path dbFile = tempDir.resolve("datamart.db");
        new DatamartStore(dbFile);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1));
        }
    }

    @Test
    void storesAndReadsBackABook() throws SQLException {
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));

        store.indexBook(BOOK, Map.of("darcy", 3, "elizabeth", 5));

        assertEquals(Optional.of(BOOK), store.findBook(1342));
        assertEquals(List.of(1342), store.booksForTerm("darcy"));
        assertEquals(2, store.countPostings(1342));
        assertTrue(store.findBook(11).isEmpty());
    }

    @Test
    void keepsNullMetadataAsNull() throws SQLException {
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));
        BookRecord unknown = new BookRecord(7, "Untitled", null, null, null);

        store.indexBook(unknown, Map.of("word", 1));

        assertEquals(Optional.of(unknown), store.findBook(7));
    }

    @Test
    void booksForTermAreOrderedById() throws SQLException {
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));

        store.indexBook(new BookRecord(84, "Frankenstein", null, "en", 1993), Map.of("monster", 1));
        store.indexBook(new BookRecord(11, "Alice", null, "en", 2008), Map.of("monster", 1));

        assertEquals(List.of(11, 84), store.booksForTerm("monster"));
    }

    @Test
    void indexingTheSameBookTwiceIsIdempotent() throws SQLException {
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));
        Map<String, Integer> terms = Map.of("darcy", 3, "elizabeth", 5, "bennet", 2);

        store.indexBook(BOOK, terms);
        store.indexBook(BOOK, terms);

        assertEquals(1, store.countBooks());
        assertEquals(3, store.countPostings(1342));
    }

    @Test
    void reindexingReplacesThePreviousPostings() throws SQLException {
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));

        store.indexBook(BOOK, Map.of("darcy", 3, "elizabeth", 5));
        store.indexBook(BOOK, Map.of("wickham", 1));

        assertEquals(List.of(), store.booksForTerm("darcy"));
        assertEquals(List.of(1342), store.booksForTerm("wickham"));
        assertEquals(1, store.countPostings(1342));
    }

    @Test
    void aFailedIndexingLeavesNoTraceOfTheBook() throws SQLException {
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));
        Map<String, Integer> terms = new HashMap<>();
        terms.put("darcy", 3);
        terms.put(null, 1);

        assertThrows(SQLException.class, () -> store.indexBook(BOOK, terms));

        assertTrue(store.findBook(1342).isEmpty());
        assertEquals(0, store.countPostings(1342));
        assertEquals(List.of(), store.booksForTerm("darcy"));
    }

    @Test
    void aFailedReindexingKeepsThePreviousVersion() throws SQLException {
        DatamartStore store = new DatamartStore(tempDir.resolve("datamart.db"));
        store.indexBook(BOOK, Map.of("darcy", 3));
        Map<String, Integer> broken = new HashMap<>();
        broken.put("wickham", 1);
        broken.put(null, 1);

        assertThrows(SQLException.class, () -> store.indexBook(BOOK, broken));

        assertEquals(List.of(1342), store.booksForTerm("darcy"));
        assertEquals(List.of(), store.booksForTerm("wickham"));
        assertEquals(1, store.countPostings(1342));
    }
}
