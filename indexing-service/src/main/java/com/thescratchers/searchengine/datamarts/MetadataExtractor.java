package com.thescratchers.searchengine.datamarts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetadataExtractor {

    private static final String DEFAULT_DB_PATH = "data/datamarts/metadata.db";

    /** Title / author / language parsed from a Project Gutenberg header. */
    public record ParsedHeader(String title, String author, String language) {}

    /** One row of the books table. */
    public record BookRecord(int bookId, String title, String author, String language) {}

    private static final String DDL =
            "CREATE TABLE IF NOT EXISTS books ("
            + "book_id  INTEGER PRIMARY KEY, "
            + "title    TEXT, "
            + "author   TEXT, "
            + "language TEXT"
            + ");";

    private static final String UPSERT =
            "INSERT OR REPLACE INTO books (book_id, title, author, language) "
            + "VALUES (?, ?, ?, ?)";

    private static final String SELECT_BY_ID =
            "SELECT book_id, title, author, language FROM books WHERE book_id = ?";

    private static final String SELECT_BY_AUTHOR =
            "SELECT book_id, title, author, language FROM books WHERE author LIKE ?";

    private static final Pattern TITLE_PATTERN =
            Pattern.compile("^Title:\\s*(.+)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern AUTHOR_PATTERN =
            Pattern.compile("^Author:\\s*(.+)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern LANGUAGE_PATTERN =
            Pattern.compile("^Language:\\s*(.+)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private final String dbPath;
    private final String dbUrl;

    public MetadataExtractor() {
        this(DEFAULT_DB_PATH);
    }

    /** Uses a custom SQLite file (the benchmark stores its synthetic rows in a scratch DB). */
    public MetadataExtractor(String dbPath) {
        this.dbPath = dbPath;
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    public void extractAndStoreMetadata(int bookId, String headerFilePath)
            throws IOException, SQLException {

        Path headerPath = Paths.get(headerFilePath);
        if (!Files.exists(headerPath)) {
            throw new IOException("Header file not found: " + headerPath.toAbsolutePath());
        }

        ParsedHeader parsed = parseHeader(Files.readString(headerPath));

        upsertBook(bookId, parsed.title(), parsed.author(), parsed.language());

        System.out.println("[METADATA] Book " + bookId
                + " stored — title=\"" + parsed.title() + "\""
                + ", author=\"" + parsed.author() + "\""
                + ", language=\"" + parsed.language() + "\"");
    }

    /** Extracts title, author and language from a Gutenberg header ("Unknown" if a field is missing). */
    public static ParsedHeader parseHeader(String headerText) {
        return new ParsedHeader(
                extractField(headerText, TITLE_PATTERN),
                extractField(headerText, AUTHOR_PATTERN),
                extractField(headerText, LANGUAGE_PATTERN));
    }

    /** Inserts a book, or replaces it if the id already exists. One connection per call. */
    public void upsertBook(int bookId, String title, String author, String language)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(UPSERT)) {
            pstmt.setInt(1, bookId);
            pstmt.setString(2, title);
            pstmt.setString(3, author);
            pstmt.setString(4, language);
            pstmt.executeUpdate();
        }
    }

    /** Primary-key lookup. One connection per call. */
    public Optional<BookRecord> findById(int bookId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(SELECT_BY_ID)) {
            pstmt.setInt(1, bookId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
            }
        }
    }

    /** All books whose author contains the given text (case-insensitive LIKE). One connection per call. */
    public List<BookRecord> findByAuthor(String author) throws SQLException {
        List<BookRecord> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(SELECT_BY_AUTHOR)) {
            pstmt.setString(1, "%" + author + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(readRow(rs));
                }
            }
        }
        return rows;
    }

    private static BookRecord readRow(ResultSet rs) throws SQLException {
        return new BookRecord(
                rs.getInt("book_id"),
                rs.getString("title"),
                rs.getString("author"),
                rs.getString("language"));
    }

    private void initSchema() {
        try {
            Path parent = Paths.get(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection conn = DriverManager.getConnection(dbUrl);
                 Statement  stmt = conn.createStatement()) {
                stmt.execute(DDL);
            }
        } catch (IOException | SQLException e) {
            System.err.println("[METADATA] Schema init failed: " + e.getMessage());
        }
    }

    private static String extractField(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "Unknown";
    }
}
