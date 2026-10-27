package com.thescratchers.searchengine.indexing.source;

import com.thescratchers.searchengine.indexing.IngestionLogFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionLogReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void findsAvailableBooksWithTheRecordedPaths() throws SQLException {
        Path db = tempDir.resolve("ingestion.db");
        new IngestionLogFixture(db).available(1342);

        Optional<BookFiles> files = new IngestionLogReader(db).find(1342);

        assertEquals(Optional.of(new BookFiles(1342,
                IngestionLogFixture.SAMPLES.resolve("1342.header.txt"),
                IngestionLogFixture.SAMPLES.resolve("1342.body.txt"))), files);
    }

    @Test
    void ignoresFailedAndUnknownBooks() throws SQLException {
        Path db = tempDir.resolve("ingestion.db");
        new IngestionLogFixture(db).failed(5);
        IngestionLogReader reader = new IngestionLogReader(db);

        assertTrue(reader.find(5).isEmpty());
        assertTrue(reader.find(999).isEmpty());
    }

    @Test
    void listsOnlyAvailableBooksOrderedById() throws SQLException {
        Path db = tempDir.resolve("ingestion.db");
        new IngestionLogFixture(db).available(1342).failed(5).available(11);

        List<Integer> ids = new IngestionLogReader(db).listAvailable().stream().map(BookFiles::bookId).toList();

        assertEquals(List.of(11, 1342), ids);
    }

    @Test
    void aMissingDatabaseMeansNoBookIsAvailableAndIsNotCreated() throws SQLException {
        Path db = tempDir.resolve("datalake").resolve("ingestion.db");
        IngestionLogReader reader = new IngestionLogReader(db);

        assertTrue(reader.find(1342).isEmpty());
        assertTrue(reader.listAvailable().isEmpty());
        assertFalse(Files.exists(db));
    }
}
