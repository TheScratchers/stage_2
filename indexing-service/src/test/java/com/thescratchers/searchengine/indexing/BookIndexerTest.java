package com.thescratchers.searchengine.indexing;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.common.Sqlite;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookIndexerTest {

    private static final Path SAMPLES = Path.of("..", "samples", "books");

    @TempDir
    Path tempDir;

    private Path dbFile;
    private DatamartStore store;
    private BookIndexer indexer;

    @BeforeEach
    void setUp() throws SQLException {
        dbFile = tempDir.resolve("datamart.db");
        store = new DatamartStore(dbFile);
        indexer = new BookIndexer(store);
    }

    @Test
    void indexesSampleBooksIntoTheDatamart() throws IOException, SQLException {
        index(1342);
        index(11);

        assertTrue(store.booksForTerm("darcy").contains(1342));
        assertFalse(store.booksForTerm("darcy").contains(11));
        assertTrue(store.booksForTerm("wonderland").contains(11));
        assertTrue(frequency("elizabeth", 1342) > 0);
        assertEquals(2, store.countBooks());
    }

    @Test
    void storesTheParsedMetadata() throws IOException, SQLException {
        index(1342);

        assertEquals(Optional.of(new BookRecord(1342, "Pride and Prejudice", "Jane Austen", "en", 1998)),
                store.findBook(1342));
    }

    @Test
    void reportsDistinctTermsAndTokens() throws IOException, SQLException {
        BookIndexer.IndexResult result = index(1342);

        assertEquals(1342, result.bookId());
        assertEquals(store.countPostings(1342), result.distinctTerms());
        assertTrue(result.totalTokens() > result.distinctTerms());
    }

    @Test
    void missingBodyFileThrowsIOException() {
        Path header = SAMPLES.resolve("1342.header.txt");
        Path body = tempDir.resolve("missing.body.txt");

        IOException e = assertThrows(IOException.class, () -> indexer.index(1342, header, body));

        assertTrue(e.getMessage().contains("missing.body.txt"));
    }

    private BookIndexer.IndexResult index(int bookId) throws IOException, SQLException {
        return indexer.index(bookId,
                SAMPLES.resolve(bookId + ".header.txt"),
                SAMPLES.resolve(bookId + ".body.txt"));
    }

    private int frequency(String term, int bookId) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT frequency FROM postings WHERE term = ? AND book_id = ?")) {
            stmt.setString(1, term);
            stmt.setInt(2, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
