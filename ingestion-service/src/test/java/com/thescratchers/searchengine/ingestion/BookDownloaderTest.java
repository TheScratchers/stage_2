package com.thescratchers.searchengine.ingestion;

import com.thescratchers.searchengine.ingestion.datalake.DatalakeLayout;
import com.thescratchers.searchengine.ingestion.datalake.DatalakeWriter;
import com.thescratchers.searchengine.ingestion.datalake.StoredBook;
import com.thescratchers.searchengine.ingestion.gutenberg.DownloadException;
import com.thescratchers.searchengine.ingestion.gutenberg.GutenbergClient;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises the whole download path against a fake Gutenberg server, without network access. */
class BookDownloaderTest {

    private static final Instant NOW = Instant.parse("2026-10-21T18:30:00Z");

    @TempDir
    Path tempDir;

    private Javalin fakeGutenberg;
    private BookDownloader downloader;

    @BeforeEach
    void startFakeGutenberg() throws Exception {
        String pride = SampleBooks.rawText(1342);
        fakeGutenberg = Javalin.create()
                .get("/cache/epub/1342/pg1342.txt", ctx -> ctx.result(pride))
                .get("/cache/epub/5/pg5.txt", ctx -> ctx.result("Title: X\nno markers here"))
                .get("/cache/epub/7/pg7.txt", ctx -> ctx.status(500))
                .get("/cache/epub/9/pg9.txt", ctx -> {
                    Thread.sleep(2500);
                    ctx.result(pride);
                })
                .start(0);
        GutenbergClient client = new GutenbergClient("http://localhost:" + fakeGutenberg.port(), Duration.ofSeconds(1));
        DatalakeWriter writer = new DatalakeWriter(tempDir.resolve("datalake"), DatalakeLayout.TIME_BASED);
        downloader = new BookDownloader(client, writer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stopFakeGutenberg() {
        fakeGutenberg.stop();
    }

    @Test
    void downloadsSplitsAndStoresTheBook() throws Exception {
        StoredBook stored = downloader.download(1342);

        assertEquals(tempDir.resolve("datalake").resolve("20261021").resolve("18"), stored.directory());
        assertEquals(SampleBooks.header(1342), Files.readString(stored.headerFile()));
        assertEquals(SampleBooks.body(1342), Files.readString(stored.bodyFile()));
    }

    @Test
    void unknownBookIsNotFound() {
        assertReason(DownloadException.Reason.NOT_FOUND, 404);
    }

    @Test
    void serverErrorIsUpstreamError() {
        assertReason(DownloadException.Reason.UPSTREAM_ERROR, 7);
    }

    @Test
    void slowServerTimesOutAsUpstreamError() {
        assertReason(DownloadException.Reason.UPSTREAM_ERROR, 9);
    }

    @Test
    void textWithoutMarkersIsInvalidFormatAndNothingIsStored() {
        assertReason(DownloadException.Reason.INVALID_FORMAT, 5);
        assertFalse(Files.exists(tempDir.resolve("datalake")));
    }

    @Test
    void nonPositiveIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> downloader.download(0));
    }

    private void assertReason(DownloadException.Reason expected, int bookId) {
        DownloadException e = assertThrows(DownloadException.class, () -> downloader.download(bookId));
        assertEquals(expected, e.reason());
        assertEquals(bookId, e.bookId());
    }
}
