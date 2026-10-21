package com.thescratchers.searchengine.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTest {

    @TempDir
    Path tempDir;

    @Test
    void createsMissingDirectoriesOnCleanStart() throws SQLException {
        Path db = tempDir.resolve("does").resolve("not").resolve("exist").resolve("test.db");

        try (Connection conn = Sqlite.open(db); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t (x INTEGER)");
        }

        assertTrue(Files.exists(db));
    }

    @Test
    void appliesAgreedPragmas() throws SQLException {
        try (Connection conn = Sqlite.open(tempDir.resolve("pragmas.db"))) {
            assertEquals("wal", pragma(conn, "journal_mode"));
            assertEquals("1", pragma(conn, "synchronous"));          // 1 = NORMAL
            assertEquals(String.valueOf(Sqlite.BUSY_TIMEOUT_MS), pragma(conn, "busy_timeout"));
        }
    }

    @Test
    void readOnlyConnectionSeesCommittedDataButCannotWrite() throws SQLException {
        Path db = tempDir.resolve("ro.db");
        try (Connection conn = Sqlite.open(db); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t (x INTEGER)");
            stmt.execute("INSERT INTO t VALUES (42)");
        }

        try (Connection conn = Sqlite.openReadOnly(db); Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT x FROM t")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
            assertThrows(SQLException.class, () -> stmt.execute("INSERT INTO t VALUES (7)"));
        }
    }

    @Test
    void readOnlyFailsWhenDatabaseIsMissing() {
        assertThrows(SQLException.class, () -> Sqlite.openReadOnly(tempDir.resolve("missing.db")));
    }

    private static String pragma(Connection conn, String name) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
