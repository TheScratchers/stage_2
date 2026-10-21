package com.thescratchers.searchengine.common;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens SQLite connections with the settings agreed for every database of the project.
 *
 * <ul>
 *   <li>{@code journal_mode=WAL}: readers (Search) see the last committed state while a writer
 *       (Indexing) is working, and never a half-written update.</li>
 *   <li>{@code synchronous=NORMAL}: durable across process crashes in WAL mode, without the cost
 *       of {@code FULL}. Stage 1 used {@code OFF}, which could lose committed data.</li>
 *   <li>{@code busy_timeout=5000}: a writer waits up to 5 s for the lock instead of failing
 *       immediately when another process is writing.</li>
 * </ul>
 *
 * <p>Connections are not thread-safe: open one per task or request and close it when done.
 */
public final class Sqlite {

    /** Milliseconds a connection waits for a lock held by another connection or process. */
    public static final int BUSY_TIMEOUT_MS = 5000;

    private Sqlite() {
    }

    /**
     * Opens a read-write connection, creating the parent directories (and the database file) if
     * they do not exist yet, so a service also starts on a fresh checkout without {@code data/}.
     */
    public static Connection open(Path dbFile) throws SQLException {
        createParentDirectories(dbFile);
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        return DriverManager.getConnection(url(dbFile), config.toProperties());
    }

    /**
     * Opens a read-only connection to an existing database. Writes through it fail.
     *
     * @throws SQLException if the database does not exist yet
     */
    public static Connection openReadOnly(Path dbFile) throws SQLException {
        if (!Files.exists(dbFile)) {
            throw new SQLException("Database does not exist: " + dbFile.toAbsolutePath());
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        return DriverManager.getConnection(url(dbFile), config.toProperties());
    }

    private static String url(Path dbFile) {
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    private static void createParentDirectories(Path dbFile) throws SQLException {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new SQLException("Cannot create directory " + parent, e);
        }
    }
}
