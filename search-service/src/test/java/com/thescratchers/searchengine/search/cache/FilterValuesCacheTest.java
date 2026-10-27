package com.thescratchers.searchengine.search.cache;

import com.thescratchers.searchengine.common.Sqlite;
import com.thescratchers.searchengine.search.store.DatamartReader;
import com.thescratchers.searchengine.search.store.TestDatamart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterValuesCacheTest {

    @TempDir
    Path tempDir;

    private Path datamart;
    private FilterValuesCache cache;

    @BeforeEach
    void createDatamart() throws SQLException {
        datamart = TestDatamart.create(tempDir.resolve("datamart.db"));
        cache = new FilterValuesCache(new DatamartReader(datamart));
    }

    @Test
    void isDisabledUntilTheSubscriptionIsEstablished() throws Exception {
        assertFalse(cache.isEnabled());
        assertEquals(6, cache.get().totalBooks());

        addBook(7);

        assertEquals(7, cache.get().totalBooks());
    }

    @Test
    void servesCachedValuesWhileEnabled() throws Exception {
        cache.enable();
        assertEquals(6, cache.get().totalBooks());

        addBook(7);

        assertEquals(6, cache.get().totalBooks());
    }

    @Test
    void invalidationReloadsTheValues() throws Exception {
        cache.enable();
        cache.get();
        addBook(7);

        cache.invalidate();

        assertEquals(7, cache.get().totalBooks());
    }

    @Test
    void disablingStopsCaching() throws Exception {
        cache.enable();
        cache.get();

        cache.disable();
        addBook(7);

        assertFalse(cache.isEnabled());
        assertEquals(7, cache.get().totalBooks());
        addBook(8);
        assertEquals(8, cache.get().totalBooks());
    }

    @Test
    void enablingDropsValuesCachedBeforeAnOutage() throws Exception {
        cache.enable();
        cache.get();
        cache.disable();
        addBook(7);

        cache.enable();

        assertTrue(cache.isEnabled());
        assertEquals(7, cache.get().totalBooks());
    }

    private void addBook(int bookId) throws SQLException {
        try (Connection conn = Sqlite.open(datamart); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO books VALUES (" + bookId + ", 'New', NULL, 'en', 2020, '2026-10-27T10:00:00Z')");
        }
    }
}
