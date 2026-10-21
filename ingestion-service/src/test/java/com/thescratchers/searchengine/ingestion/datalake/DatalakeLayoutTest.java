package com.thescratchers.searchengine.ingestion.datalake;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatalakeLayoutTest {

    private static final Path BASE = Path.of("datalake");
    private static final Instant AT = Instant.parse("2026-10-08T14:05:00Z");

    @Test
    void timeBasedUsesUtcDateAndHour() {
        assertEquals(BASE.resolve("20261008").resolve("14"), DatalakeLayout.TIME_BASED.directoryFor(BASE, 1342, AT));
    }

    @Test
    void bookBasedUsesTheId() {
        assertEquals(BASE.resolve("1342"), DatalakeLayout.BOOK_BASED.directoryFor(BASE, 1342, AT));
    }

    @Test
    void batchBasedGroupsThousandIds() {
        assertEquals(BASE.resolve("batch_1000-1999"), DatalakeLayout.BATCH_BASED.directoryFor(BASE, 1342, AT));
        assertEquals(BASE.resolve("batch_0-999"), DatalakeLayout.BATCH_BASED.directoryFor(BASE, 11, AT));
    }

    @Test
    void fileNamesFollowTheStage1Convention() {
        Path dir = Path.of("d");
        assertEquals(dir.resolve("1342.header.txt"), DatalakeLayout.headerFile(dir, 1342));
        assertEquals(dir.resolve("1342.body.txt"), DatalakeLayout.bodyFile(dir, 1342));
    }

    @Test
    void layoutIsSelectedByLabel() {
        assertEquals(DatalakeLayout.BOOK_BASED, DatalakeLayout.fromLabel(" Book_Based "));
        assertThrows(IllegalArgumentException.class, () -> DatalakeLayout.fromLabel("by_author"));
        assertThrows(IllegalArgumentException.class, () -> DatalakeLayout.fromLabel(null));
    }
}
