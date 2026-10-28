package com.thescratchers.searchengine.indexing.source;

import com.thescratchers.searchengine.common.Sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link BookSource} backed by the {@code ingestion_log} table of {@code datalake/ingestion.db}.
 *
 * <p>The database belongs to the Ingestion Service; this class only opens it read-only (see
 * {@code docs/contracts.md}, section 2). Only rows with {@code status = 'available'} are returned,
 * because Ingestion writes that status after both files have been moved to their final names. The
 * paths are used exactly as Ingestion recorded them. While the database does not exist yet, no
 * book is available. Each call opens and closes its own connection.
 */
public final class IngestionLogReader implements BookSource {

    private static final String SELECT_ONE =
            "SELECT book_id, header_path, body_path FROM ingestion_log WHERE book_id = ? AND status = 'available'";
    private static final String SELECT_ALL =
            "SELECT book_id, header_path, body_path FROM ingestion_log WHERE status = 'available' ORDER BY book_id";

    private final Path dbFile;

    /** Creates a reader of the ingestion log at {@code dbFile} ({@code DATA_DIR/datalake/ingestion.db}). */
    public IngestionLogReader(Path dbFile) {
        this.dbFile = dbFile;
    }

    @Override
    public Optional<BookFiles> find(int bookId) throws SQLException {
        if (!Files.exists(dbFile)) {
            return Optional.empty();
        }
        try (Connection conn = Sqlite.openReadOnly(dbFile);
             PreparedStatement stmt = conn.prepareStatement(SELECT_ONE)) {
            stmt.setInt(1, bookId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public List<BookFiles> listAvailable() throws SQLException {
        List<BookFiles> books = new ArrayList<>();
        if (!Files.exists(dbFile)) {
            return books;
        }
        try (Connection conn = Sqlite.openReadOnly(dbFile);
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                books.add(readRow(rs));
            }
        }
        return books;
    }

    private static BookFiles readRow(ResultSet rs) throws SQLException {
        return new BookFiles(
                rs.getInt("book_id"),
                Path.of(rs.getString("header_path")),
                Path.of(rs.getString("body_path")));
    }
}
