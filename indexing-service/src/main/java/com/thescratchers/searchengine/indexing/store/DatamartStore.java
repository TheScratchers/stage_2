package com.thescratchers.searchengine.indexing.store;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.common.Sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writer of the SQLite datamart ({@code datamart.db}): book metadata, the inverted index with term
 * frequencies, and index metadata. The schema is the team contract read by the Search Service.
 *
 * <p>Every method opens and closes its own connection through {@link Sqlite#open}, so an instance
 * holds no connection and can be shared by several threads; SQLite serialises the writers.
 */
public final class DatamartStore {

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS books ("
                + "book_id INTEGER PRIMARY KEY, "
                + "title TEXT NOT NULL, "
                + "author TEXT, "
                + "language TEXT, "
                + "year INTEGER, "
                + "indexed_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS postings ("
                + "term TEXT NOT NULL, "
                + "book_id INTEGER NOT NULL, "
                + "frequency INTEGER NOT NULL, "
                + "PRIMARY KEY (term, book_id))",
        "CREATE INDEX IF NOT EXISTS idx_postings_book ON postings(book_id)",
        "CREATE TABLE IF NOT EXISTS index_meta (key TEXT PRIMARY KEY, value TEXT)"
    };

    private static final String DELETE_POSTINGS = "DELETE FROM postings WHERE book_id = ?";
    private static final String INSERT_POSTING =
            "INSERT INTO postings(term, book_id, frequency) VALUES (?, ?, ?)";
    private static final String UPSERT_BOOK =
            "INSERT OR REPLACE INTO books(book_id, title, author, language, year, indexed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String UPSERT_LAST_UPDATE =
            "INSERT OR REPLACE INTO index_meta(key, value) VALUES ('last_update', ?)";
    private static final String SELECT_BOOK =
            "SELECT book_id, title, author, language, year FROM books WHERE book_id = ?";
    private static final String SELECT_BOOKS_FOR_TERM =
            "SELECT book_id FROM postings WHERE term = ? ORDER BY book_id";
    private static final String COUNT_BOOKS = "SELECT COUNT(*) FROM books";
    private static final String COUNT_POSTINGS = "SELECT COUNT(*) FROM postings WHERE book_id = ?";
    private static final String SELECT_BOOK_IDS = "SELECT book_id FROM books ORDER BY book_id";
    private static final String DELETE_BOOK = "DELETE FROM books WHERE book_id = ?";
    private static final String SELECT_LAST_UPDATE = "SELECT value FROM index_meta WHERE key = 'last_update'";
    private static final String WAL_SUFFIX = "-wal";

    private final Path dbFile;

    /**
     * Opens the datamart at {@code dbFile}, creating its parent directories, the file and the
     * schema if they do not exist yet.
     *
     * @throws SQLException if the database cannot be opened or the schema cannot be created
     */
    public DatamartStore(Path dbFile) throws SQLException {
        this.dbFile = dbFile;
        try (Connection conn = Sqlite.open(dbFile);
             Statement stmt = conn.createStatement()) {
            for (String ddl : SCHEMA) {
                stmt.execute(ddl);
            }
        }
    }

    /**
     * Stores a book and its postings in a single transaction, replacing any previous version of the
     * book: its old postings are deleted, the new ones inserted, the {@code books} row upserted and
     * {@code index_meta.last_update} updated. Either all of it is committed or nothing is, so
     * re-indexing a book never duplicates rows and a failure never leaves it half indexed.
     *
     * @param book            metadata of the book
     * @param termFrequencies occurrences of each term in the book body
     * @throws SQLException if the transaction fails; it has been rolled back
     */
    public void indexBook(BookRecord book, Map<String, Integer> termFrequencies) throws SQLException {
        String now = Instant.now().toString();
        try (Connection conn = Sqlite.open(dbFile)) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(DELETE_POSTINGS)) {
                    delete.setInt(1, book.bookId());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement(INSERT_POSTING)) {
                    for (Map.Entry<String, Integer> entry : termFrequencies.entrySet()) {
                        insert.setString(1, entry.getKey());
                        insert.setInt(2, book.bookId());
                        insert.setInt(3, entry.getValue());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement upsert = conn.prepareStatement(UPSERT_BOOK)) {
                    upsert.setInt(1, book.bookId());
                    upsert.setString(2, book.title());
                    upsert.setString(3, book.author());
                    upsert.setString(4, book.language());
                    if (book.year() == null) {
                        upsert.setNull(5, Types.INTEGER);
                    } else {
                        upsert.setInt(5, book.year());
                    }
                    upsert.setString(6, now);
                    upsert.executeUpdate();
                }
                try (PreparedStatement meta = conn.prepareStatement(UPSERT_LAST_UPDATE)) {
                    meta.setString(1, now);
                    meta.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Removes a book and its postings in a single transaction and updates
     * {@code index_meta.last_update}. Removing a book that is not indexed changes nothing else.
     *
     * @return {@code true} if the book was indexed and has been removed
     * @throws SQLException if the transaction fails; it has been rolled back
     */
    public boolean deleteBook(int bookId) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile)) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(DELETE_POSTINGS)) {
                    delete.setInt(1, bookId);
                    delete.executeUpdate();
                }
                int removed;
                try (PreparedStatement delete = conn.prepareStatement(DELETE_BOOK)) {
                    delete.setInt(1, bookId);
                    removed = delete.executeUpdate();
                }
                try (PreparedStatement meta = conn.prepareStatement(UPSERT_LAST_UPDATE)) {
                    meta.setString(1, Instant.now().toString());
                    meta.executeUpdate();
                }
                conn.commit();
                return removed > 0;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** Returns the metadata of a book, or empty if it has not been indexed. */
    public Optional<BookRecord> findBook(int bookId) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(SELECT_BOOK)) {
            stmt.setInt(1, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                int year = rs.getInt("year");
                Integer nullableYear = rs.wasNull() ? null : year;
                return Optional.of(new BookRecord(
                        rs.getInt("book_id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("language"),
                        nullableYear));
            }
        }
    }

    /** Returns the ids of the books containing {@code term}, ordered by book id. */
    public List<Integer> booksForTerm(String term) throws SQLException {
        List<Integer> bookIds = new ArrayList<>();
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(SELECT_BOOKS_FOR_TERM)) {
            stmt.setString(1, term);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    bookIds.add(rs.getInt(1));
                }
            }
        }
        return bookIds;
    }

    /** Returns the number of indexed books. */
    public int countBooks() throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(COUNT_BOOKS);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Returns the ids of every indexed book, ordered by book id. */
    public List<Integer> bookIds() throws SQLException {
        List<Integer> bookIds = new ArrayList<>();
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(SELECT_BOOK_IDS);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                bookIds.add(rs.getInt(1));
            }
        }
        return bookIds;
    }

    /** Returns the time of the last committed change to the index, or empty if there has been none. */
    public Optional<String> lastUpdate() throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(SELECT_LAST_UPDATE);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        }
    }

    /**
     * Returns the bytes the datamart occupies on disk: the database file plus its write-ahead log,
     * which holds committed pages until SQLite checkpoints them into the database file.
     *
     * @throws IOException if the size of an existing file cannot be read
     */
    public long sizeBytes() throws IOException {
        Path wal = dbFile.resolveSibling(dbFile.getFileName() + WAL_SUFFIX);
        long size = 0;
        for (Path file : List.of(dbFile, wal)) {
            if (Files.exists(file)) {
                size += Files.size(file);
            }
        }
        return size;
    }

    /** Returns the number of postings (distinct terms) stored for a book. */
    public int countPostings(int bookId) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(COUNT_POSTINGS)) {
            stmt.setInt(1, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
