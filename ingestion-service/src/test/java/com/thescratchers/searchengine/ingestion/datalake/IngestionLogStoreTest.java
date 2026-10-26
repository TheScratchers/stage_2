package com.thescratchers.searchengine.ingestion.datalake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionLogStoreTest {

    @Test
    void testRecordAvailableAndQuery(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("datalake/ingestion.db");
        IngestionLogStore store = new IngestionLogStore(db);

        Instant now = Instant.parse("2026-10-26T12:00:00Z");
        assertFalse(store.isAvailable(1342));
        assertEquals(0, store.countAvailable());
        assertTrue(store.listAvailable().isEmpty());

        store.recordAvailable(1342, "datalake/20261026/12/1342.header.txt", "datalake/20261026/12/1342.body.txt", now);

        assertTrue(store.isAvailable(1342));
        assertEquals(1, store.countAvailable());
        assertEquals(List.of(1342), store.listAvailable());

        Optional<IngestionRecord> record = store.findById(1342);
        assertTrue(record.isPresent());
        assertEquals(1342, record.get().bookId());
        assertEquals("available", record.get().status());
        assertEquals("datalake/20261026/12/1342.header.txt", record.get().headerPath());
        assertEquals("datalake/20261026/12/1342.body.txt", record.get().bodyPath());
        assertEquals(now, record.get().ingestedAt());
    }

    @Test
    void testRecordFailed(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("datalake/ingestion.db");
        IngestionLogStore store = new IngestionLogStore(db);

        Instant now = Instant.parse("2026-10-26T13:00:00Z");
        store.recordFailed(9999, now);

        assertFalse(store.isAvailable(9999));
        assertEquals(0, store.countAvailable());

        Optional<IngestionRecord> record = store.findById(9999);
        assertTrue(record.isPresent());
        assertEquals(9999, record.get().bookId());
        assertEquals("failed", record.get().status());
        assertNull(record.get().headerPath());
        assertNull(record.get().bodyPath());
    }

    @Test
    void testUpsertUpdatesExisting(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("datalake/ingestion.db");
        IngestionLogStore store = new IngestionLogStore(db);

        Instant t1 = Instant.parse("2026-10-26T10:00:00Z");
        store.recordFailed(5, t1);
        assertFalse(store.isAvailable(5));

        Instant t2 = Instant.parse("2026-10-26T11:00:00Z");
        store.recordAvailable(5, "datalake/5.header.txt", "datalake/5.body.txt", t2);
        assertTrue(store.isAvailable(5));
        assertEquals(1, store.countAvailable());

        Optional<IngestionRecord> record = store.findById(5);
        assertTrue(record.isPresent());
        assertEquals("available", record.get().status());
        assertEquals(t2, record.get().ingestedAt());
    }

    @Test
    void testListAvailableOrdersById(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("datalake/ingestion.db");
        IngestionLogStore store = new IngestionLogStore(db);

        Instant now = Instant.now();
        store.recordAvailable(1342, "h", "b", now);
        store.recordAvailable(11, "h", "b", now);
        store.recordAvailable(2701, "h", "b", now);
        store.recordFailed(50, now);

        assertEquals(3, store.countAvailable());
        assertEquals(List.of(11, 1342, 2701), store.listAvailable());
    }
}
