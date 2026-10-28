package com.thescratchers.searchengine.ingestion.datalake;

import com.thescratchers.searchengine.common.Config;
import com.thescratchers.searchengine.common.Sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Storage and query interface for the {@code ingestion_log} table in {@code datalake/ingestion.db}.
 *
 * <p>Tracks whether books are available or failed in the datalake. Every operation opens and closes
 * its own SQLite connection with WAL mode enabled via {@link Sqlite#open}, allowing concurrent
 * thread execution while SQLite serialises write transactions.
 */
public final class IngestionLogStore {

    private static final String DDL = "CREATE TABLE IF NOT EXISTS ingestion_log ("
            + "book_id INTEGER PRIMARY KEY, "
            + "status TEXT NOT NULL, "
            + "header_path TEXT, "
            + "body_path TEXT, "
            + "ingested_at TEXT NOT NULL)";

    private static final String UPSERT_RECORD =
            "INSERT OR REPLACE INTO ingestion_log(book_id, status, header_path, body_path, ingested_at) "
            + "VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID =
            "SELECT book_id, status, header_path, body_path, ingested_at FROM ingestion_log WHERE book_id = ?";

    private static final String SELECT_AVAILABLE_IDS =
            "SELECT book_id FROM ingestion_log WHERE status = 'available' ORDER BY book_id ASC";

    private static final String COUNT_AVAILABLE =
            "SELECT COUNT(*) FROM ingestion_log WHERE status = 'available'";

    private final Path dbFile;

    /**
     * Initializes the store at {@code dbFile}, creating directories and the schema if missing.
     *
     * @param dbFile path to {@code ingestion.db}
     * @throws SQLException if opening SQLite or executing the DDL fails
     */
    public IngestionLogStore(Path dbFile) throws SQLException {
        this.dbFile = dbFile;
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
        } catch (IOException e) {
            throw new SQLException("Failed to create parent directory for " + dbFile, e);
        }
        try (Connection conn = Sqlite.open(dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL);
        }
    }

    /** Creates an {@link IngestionLogStore} pointing to {@code <DATA_DIR>/datalake/ingestion.db}. */
    public static IngestionLogStore fromConfig() throws SQLException {
        return new IngestionLogStore(Config.datalakeDir().resolve("ingestion.db"));
    }

    /**
     * Records or updates a book as {@code 'available'}.
     *
     * @param bookId     Project Gutenberg book id
     * @param headerPath relative path to the header file
     * @param bodyPath   relative path to the body file
     * @param ingestedAt timestamp of successful ingestion
     * @throws SQLException if the database write fails
     */
    public void recordAvailable(int bookId, String headerPath, String bodyPath, Instant ingestedAt) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement ps = conn.prepareStatement(UPSERT_RECORD)) {
            ps.setInt(1, bookId);
            ps.setString(2, IngestionRecord.STATUS_AVAILABLE);
            ps.setString(3, headerPath);
            ps.setString(4, bodyPath);
            ps.setString(5, ingestedAt.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Records or updates a book as {@code 'failed'}.
     *
     * @param bookId     Project Gutenberg book id
     * @param ingestedAt timestamp of failed ingestion attempt
     * @throws SQLException if the database write fails
     */
    public void recordFailed(int bookId, Instant ingestedAt) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement ps = conn.prepareStatement(UPSERT_RECORD)) {
            ps.setInt(1, bookId);
            ps.setString(2, IngestionRecord.STATUS_FAILED);
            ps.setNull(3, java.sql.Types.VARCHAR);
            ps.setNull(4, java.sql.Types.VARCHAR);
            ps.setString(5, ingestedAt.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Finds the ingestion record for {@code bookId}, if present.
     *
     * @param bookId Project Gutenberg book id
     * @return the record if found, otherwise empty
     * @throws SQLException if querying the database fails
     */
    public Optional<IngestionRecord> findById(int bookId) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    String header = rs.getString("header_path");
                    String body = rs.getString("body_path");
                    String ingestedAtStr = rs.getString("ingested_at");
                    Instant ingestedAt = Instant.parse(ingestedAtStr);
                    return Optional.of(new IngestionRecord(bookId, status, header, body, ingestedAt));
                }
                return Optional.empty();
            }
        }
    }

    /** Returns {@code true} if {@code bookId} is recorded with status {@code 'available'}. */
    public boolean isAvailable(int bookId) throws SQLException {
        return findById(bookId).map(IngestionRecord::isAvailable).orElse(false);
    }

    /** Returns all book IDs currently marked as {@code 'available'}, ordered ascending. */
    public List<Integer> listAvailable() throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement ps = conn.prepareStatement(SELECT_AVAILABLE_IDS);
             ResultSet rs = ps.executeQuery()) {
            List<Integer> list = new ArrayList<>();
            while (rs.next()) {
                list.add(rs.getInt(1));
            }
            return Collections.unmodifiableList(list);
        }
    }

    /** Returns the total count of books marked as {@code 'available'}. */
    public int countAvailable() throws SQLException {
        try (Connection conn = Sqlite.open(dbFile);
             PreparedStatement ps = conn.prepareStatement(COUNT_AVAILABLE);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    /** Returns the file path of this store's database. */
    public Path dbFile() {
        return dbFile;
    }
}
