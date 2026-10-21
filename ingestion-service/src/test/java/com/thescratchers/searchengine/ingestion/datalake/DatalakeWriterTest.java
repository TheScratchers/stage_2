package com.thescratchers.searchengine.ingestion.datalake;

import com.thescratchers.searchengine.ingestion.gutenberg.SplitBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatalakeWriterTest {

    private static final Instant AT = Instant.parse("2026-10-08T14:05:00Z");

    @TempDir
    Path tempDir;

    @Test
    void storesHeaderAndBodyCreatingMissingDirectories() throws Exception {
        Path root = tempDir.resolve("not-yet-created").resolve("datalake");
        DatalakeWriter writer = new DatalakeWriter(root, DatalakeLayout.TIME_BASED);

        StoredBook stored = writer.store(1342, new SplitBook("Title: Pride and Prejudice", "It is a truth"), AT);

        assertEquals(root.resolve("20261008").resolve("14"), stored.directory());
        assertEquals("Title: Pride and Prejudice", Files.readString(stored.headerFile()));
        assertEquals("It is a truth", Files.readString(stored.bodyFile()));
        assertEquals(AT, stored.ingestedAt());
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws Exception {
        DatalakeWriter writer = new DatalakeWriter(tempDir, DatalakeLayout.BOOK_BASED);

        StoredBook stored = writer.store(11, new SplitBook("h", "b"), AT);

        try (Stream<Path> files = Files.list(stored.directory())) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void storingAgainReplacesTheFiles() throws Exception {
        DatalakeWriter writer = new DatalakeWriter(tempDir, DatalakeLayout.BOOK_BASED);
        writer.store(11, new SplitBook("old header", "old body"), AT);

        StoredBook stored = writer.store(11, new SplitBook("new header", "new body"), AT);

        assertEquals("new header", Files.readString(stored.headerFile()));
        assertEquals("new body", Files.readString(stored.bodyFile()));
    }

    @Test
    void keepsNonAsciiTextIntact() throws Exception {
        DatalakeWriter writer = new DatalakeWriter(tempDir, DatalakeLayout.BOOK_BASED);

        StoredBook stored = writer.store(5, new SplitBook("Title: Café Society", "A naïve façade — déjà vu"), AT);

        assertEquals("Title: Café Society", Files.readString(stored.headerFile()));
        assertTrue(Files.readString(stored.bodyFile()).contains("déjà"));
    }
}
