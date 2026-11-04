package com.thescratchers.searchengine.indexing;

import com.thescratchers.searchengine.common.Sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Full content of a datamart, read with a read-only connection, so that two indexes can be compared
 * with {@code equals}: every {@code books} row except {@code indexed_at} (the time of indexing
 * differs between runs) and every {@code postings} row, grouped by book.
 *
 * @param books    metadata of each indexed book, by book id
 * @param postings term frequencies of each book that has postings, by book id
 */
public record IndexSnapshot(SortedMap<Integer, Book> books, SortedMap<Integer, SortedMap<String, Integer>> postings) {

    /** Stored metadata of one book. */
    public record Book(String title, String author, String language, Integer year) {
    }

    /** Reads the whole datamart at {@code dbFile}. */
    public static IndexSnapshot read(Path dbFile) throws SQLException {
        SortedMap<Integer, Book> books = new TreeMap<>();
        SortedMap<Integer, SortedMap<String, Integer>> postings = new TreeMap<>();
        try (Connection conn = Sqlite.openReadOnly(dbFile)) {
            try (PreparedStatement stmt = conn.prepareStatement(
                         "SELECT book_id, title, author, language, year FROM books");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Integer year = rs.getObject("year") == null ? null : rs.getInt("year");
                    books.put(rs.getInt("book_id"), new Book(
                            rs.getString("title"), rs.getString("author"), rs.getString("language"), year));
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("SELECT term, book_id, frequency FROM postings");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    postings.computeIfAbsent(rs.getInt("book_id"), id -> new TreeMap<>())
                            .put(rs.getString("term"), rs.getInt("frequency"));
                }
            }
        }
        return new IndexSnapshot(books, postings);
    }

    /** The part of this index that belongs to {@code bookIds}: their metadata and their postings. */
    public IndexSnapshot restrictedTo(Set<Integer> bookIds) {
        return new IndexSnapshot(filter(books, bookIds), filter(postings, bookIds));
    }

    private static <V> SortedMap<Integer, V> filter(SortedMap<Integer, V> byBook, Set<Integer> bookIds) {
        SortedMap<Integer, V> kept = new TreeMap<>();
        for (Map.Entry<Integer, V> entry : byBook.entrySet()) {
            if (bookIds.contains(entry.getKey())) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    /** Short description for assertion messages; the full content is too large to print. */
    @Override
    public String toString() {
        int rows = postings.values().stream().mapToInt(Map::size).sum();
        return "IndexSnapshot[books=" + books.keySet() + ", postings=" + rows + " rows]";
    }
}
