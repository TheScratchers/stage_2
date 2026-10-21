package com.thescratchers.searchengine.ingestion;

import com.thescratchers.searchengine.ingestion.datalake.DatalakeWriter;
import com.thescratchers.searchengine.ingestion.datalake.StoredBook;
import com.thescratchers.searchengine.ingestion.gutenberg.DownloadException;
import com.thescratchers.searchengine.ingestion.gutenberg.GutenbergClient;
import com.thescratchers.searchengine.ingestion.gutenberg.GutenbergText;
import com.thescratchers.searchengine.ingestion.gutenberg.SplitBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

/**
 * Downloads a book from Project Gutenberg, splits it into header and body and stores both files in
 * the datalake. When it returns, both files are completely written.
 *
 * <p>It is stateless and safe to call from several threads for different books.
 */
public final class BookDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(BookDownloader.class);

    private final GutenbergClient client;
    private final DatalakeWriter writer;
    private final Clock clock;

    public BookDownloader(GutenbergClient client, DatalakeWriter writer, Clock clock) {
        this.client = client;
        this.writer = writer;
        this.clock = clock;
    }

    /** Downloader wired from the configuration (Gutenberg URL, timeout, datalake path and layout). */
    public static BookDownloader fromConfig() {
        return new BookDownloader(GutenbergClient.fromConfig(), DatalakeWriter.fromConfig(), Clock.systemUTC());
    }

    /**
     * Downloads and stores {@code bookId}.
     *
     * @throws IllegalArgumentException if {@code bookId} is not positive
     * @throws DownloadException        if Gutenberg does not provide a valid text for the book
     * @throws IOException              if the files cannot be written to the datalake
     */
    public StoredBook download(int bookId) throws DownloadException, IOException {
        if (bookId <= 0) {
            throw new IllegalArgumentException("book id must be positive but was " + bookId);
        }
        long startNanos = System.nanoTime();
        SplitBook book = GutenbergText.split(bookId, client.fetch(bookId));
        StoredBook stored = writer.store(bookId, book, Instant.now(clock));
        LOG.info("Book {} stored in {} ({} ms)", bookId, stored.directory(), (System.nanoTime() - startNanos) / 1_000_000);
        return stored;
    }
}
