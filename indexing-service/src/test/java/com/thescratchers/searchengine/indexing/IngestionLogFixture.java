package com.thescratchers.searchengine.indexing;

import com.thescratchers.searchengine.common.Sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test double of the Ingestion Service's {@code ingestion.db}: creates the {@code ingestion_log}
 * table with the schema of {@code docs/contracts.md} and adds rows pointing to the bundled corpus.
 */
public final class IngestionLogFixture {

    /** {@code samples/books}, relative to the module directory where Maven runs the tests. */
    public static final Path SAMPLES = Path.of("..", "samples", "books");

    private final Path dbFile;

    /** Creates the database and the table at {@code dbFile}. */
    public IngestionLogFixture(Path dbFile) throws SQLException {
        this.dbFile = dbFile;
        try (Connection conn = Sqlite.open(dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS ingestion_log ("
                    + "book_id INTEGER PRIMARY KEY, "
                    + "status TEXT NOT NULL, "
                    + "header_path TEXT, "
                    + "body_path TEXT, "
                    + "ingested_at TEXT NOT NULL)");
        }
    }

    /** Records a sample book as available, with the paths of its files in {@code samples/books}. */
    public IngestionLogFixture available(int bookId) throws SQLException {
        return row(bookId, "available",
                SAMPLES.resolve(bookId + ".header.txt").toString(),
                SAMPLES.resolve(bookId + ".body.txt").toString());
    }

    /** Records a book as available with explicit paths (for example, files that do not exist). */
    public IngestionLogFixture available(int bookId, Path header, Path body) throws SQLException {
        return row(bookId, "available", header.toString(), body.toString());
    }

    /** Records a book whose download failed. */
    public IngestionLogFixture failed(int bookId) throws SQLException {
        return row(bookId, "failed", null, null);
    }

    /** Removes a book from the log, as if it had never been ingested. */
    public IngestionLogFixture remove(int bookId) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM ingestion_log WHERE book_id = ?")) {
            stmt.setInt(1, bookId);
            stmt.executeUpdate();
        }
        return this;
    }

    private IngestionLogFixture row(int bookId, String status, String header, String body) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO ingestion_log VALUES (?, ?, ?, ?, '2026-10-27T10:00:00Z')")) {
            stmt.setInt(1, bookId);
            stmt.setString(2, status);
            stmt.setString(3, header);
            stmt.setString(4, body);
            stmt.executeUpdate();
        }
        return this;
    }
}
