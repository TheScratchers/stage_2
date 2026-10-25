package com.thescratchers.searchengine.search.store;

import com.thescratchers.searchengine.common.Sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Builds a small test datamart with the schema of {@code docs/contracts.md}, independent of the
 * Indexing Service code, so that every expected search result can be worked out by hand.
 *
 * <pre>
 * book | author                 | lang | year | postings (term=frequency)
 * -----|------------------------|------|------|--------------------------------------
 *    1 | Mark Twain             | en   | 2004 | river=5, raft=3, adventure=2
 *    2 | Mark Twain             | en   | 2004 | adventure=4, river=1
 *    3 | Charlotte Brontë       | en   | 1998 | adventure=2, moor=7
 *    4 | (none)                 | fr   | 1998 | adventure=4, rivière=2
 *    5 | Jules Verne            | fr   | -    | adventure=1, river=9
 *    6 | Anonymous              | -    | 2010 | moor=1
 * </pre>
 */
final class TestDatamart {

    static final String LAST_UPDATE = "2026-10-25T12:00:00Z";

    private static final String[] STATEMENTS = {
        "CREATE TABLE books (book_id INTEGER PRIMARY KEY, title TEXT NOT NULL, author TEXT, "
                + "language TEXT, year INTEGER, indexed_at TEXT NOT NULL)",
        "CREATE TABLE postings (term TEXT NOT NULL, book_id INTEGER NOT NULL, frequency INTEGER NOT NULL, "
                + "PRIMARY KEY (term, book_id))",
        "CREATE INDEX idx_postings_book ON postings(book_id)",
        "CREATE TABLE index_meta (key TEXT PRIMARY KEY, value TEXT)",
    };

    private static final String[] DATA = {
        "INSERT INTO books VALUES "
                + "(1, 'Life on the River', 'Mark Twain', 'en', 2004, '2026-10-25T10:00:01Z'), "
                + "(2, 'Tales of Adventure', 'Mark Twain', 'en', 2004, '2026-10-25T10:00:02Z'), "
                + "(3, 'Moorland Stories', 'Charlotte Brontë', 'en', 1998, '2026-10-25T10:00:03Z'), "
                + "(4, 'Voyage sur la rivière', NULL, 'fr', 1998, '2026-10-25T10:00:04Z'), "
                + "(5, 'Le Fleuve', 'Jules Verne', 'fr', NULL, '2026-10-25T10:00:05Z'), "
                + "(6, 'Untitled', 'Anonymous', NULL, 2010, '2026-10-25T10:00:06Z')",
        "INSERT INTO postings VALUES "
                + "('river', 1, 5), ('raft', 1, 3), ('adventure', 1, 2), "
                + "('adventure', 2, 4), ('river', 2, 1), "
                + "('adventure', 3, 2), ('moor', 3, 7), "
                + "('adventure', 4, 4), ('rivière', 4, 2), "
                + "('adventure', 5, 1), ('river', 5, 9), "
                + "('moor', 6, 1)",
        "INSERT INTO index_meta VALUES ('last_update', '" + LAST_UPDATE + "')",
    };

    private TestDatamart() {
    }

    /** Creates the datamart with the schema and the books of the table above. */
    static Path create(Path dbFile) throws SQLException {
        run(dbFile, STATEMENTS);
        run(dbFile, DATA);
        return dbFile;
    }

    /** Creates a datamart with the schema but no books, as Indexing leaves it before the first book. */
    static Path createEmpty(Path dbFile) throws SQLException {
        run(dbFile, STATEMENTS);
        return dbFile;
    }

    private static void run(Path dbFile, String[] statements) throws SQLException {
        try (Connection conn = Sqlite.open(dbFile); Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }
}
