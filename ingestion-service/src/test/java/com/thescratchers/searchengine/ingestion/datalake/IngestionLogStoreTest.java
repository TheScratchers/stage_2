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
        assertTrue(store.findById(1342).isEmpty());
        assertTrue(store.listAvailable().isEmpty());

        store.recordAvailable(1342, "/data/datalake/20261026/12/1342.header.txt", "/data/datalake/20261026/12/1342.body.txt", now);

        assertEquals(List.of(1342), store.listAvailable());

        Optional<IngestionRecord> record = store.findById(1342);
        assertTrue(record.isPresent());
        assertEquals(1342, record.get().bookId());
        assertEquals("available", record.get().status());
        assertTrue(record.get().isAvailable());
        assertEquals("/data/datalake/20261026/12/1342.header.txt", record.get().headerPath());
        assertEquals("/data/datalake/20261026/12/1342.body.txt", record.get().bodyPath());
        assertEquals(now, record.get().ingestedAt());
    }

    @Test
    void testRecordFailed(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("datalake/ingestion.db");
        IngestionLogStore store = new IngestionLogStore(db);

        Instant now = Instant.parse("2026-10-26T13:00:00Z");
        store.recordFailed(9999, now);

        assertTrue(store.listAvailable().isEmpty());

        Optional<IngestionRecord> record = store.findById(9999);
        assertTrue(record.isPresent());
        assertEquals(9999, record.get().bookId());
        assertEquals("failed", record.get().status());
        assertFalse(record.get().isAvailable());
        assertNull(record.get().headerPath());
        assertNull(record.get().bodyPath());
    }

    @Test
    void testUpsertUpdatesExisting(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("datalake/ingestion.db");
        IngestionLogStore store = new IngestionLogStore(db);

        Instant t1 = Instant.parse("2026-10-26T10:00:00Z");
        store.recordFailed(5, t1);
        Optional<IngestionRecord> failedRecord = store.findById(5);
        assertTrue(failedRecord.isPresent());
        assertFalse(failedRecord.get().isAvailable());

        Instant t2 = Instant.parse("2026-10-26T11:00:00Z");
        store.recordAvailable(5, "/data/datalake/5.header.txt", "/data/datalake/5.body.txt", t2);
        assertEquals(List.of(5), store.listAvailable());

        Optional<IngestionRecord> record = store.findById(5);
        assertTrue(record.isPresent());
        assertEquals("available", record.get().status());
        assertTrue(record.get().isAvailable());
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

        assertEquals(List.of(11, 1342, 2701), store.listAvailable());
    }
}

