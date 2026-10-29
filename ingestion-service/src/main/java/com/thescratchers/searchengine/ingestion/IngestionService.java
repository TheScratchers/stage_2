package com.thescratchers.searchengine.ingestion;

import com.thescratchers.searchengine.ingestion.broker.BookDownloadedEvent;
import com.thescratchers.searchengine.ingestion.broker.BookDownloadedPublisher;
import com.thescratchers.searchengine.ingestion.datalake.IngestionLogStore;
import com.thescratchers.searchengine.ingestion.datalake.IngestionRecord;
import com.thescratchers.searchengine.ingestion.datalake.StoredBook;
import com.thescratchers.searchengine.ingestion.gutenberg.DownloadException;
import jakarta.jms.JMSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates the full ingestion lifecycle for books: idempotency checks against {@link IngestionLogStore},
 * fetching and atomic storage via {@link BookDownloader}, and publishing {@link BookDownloadedEvent} to ActiveMQ.
 */
public final class IngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);

    private final BookDownloader downloader;
    private final IngestionLogStore store;
    private final BookDownloadedPublisher publisher;

    public IngestionService(BookDownloader downloader, IngestionLogStore store, BookDownloadedPublisher publisher) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * Ingests {@code bookId} into the datalake if not already available, and publishes the event to the broker.
     *
     * <p>If the book is already marked {@code 'available'} in the store, no download is performed and
     * the {@link BookDownloadedEvent} is re-published to ensure downstream indexing before returning
     * {@link IngestResult#STATUS_ALREADY_AVAILABLE}. Otherwise, it downloads the text, writes header/body
     * atomically, records {@code 'available'} in SQLite with absolute paths, and publishes
     * a {@code BookDownloaded} event to ActiveMQ.
     *
     * @param bookId Project Gutenberg book id
     * @return the result of the ingestion
     * @throws IllegalArgumentException if {@code bookId <= 0}
     * @throws DownloadException        if downloading or splitting from Gutenberg fails
     * @throws IOException              if storing files to disk fails
     * @throws SQLException             if database operations fail
     * @throws JMSException             if publishing to the message broker fails
     */
    public IngestResult ingest(int bookId) throws DownloadException, IOException, SQLException, JMSException {
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be a positive integer but was " + bookId);
        }

        Optional<IngestionRecord> existing = store.findById(bookId);
        if (existing.isPresent() && existing.get().isAvailable()) {
            LOG.info("Book {} is already available in the datalake; skipping download and publishing event", bookId);
            IngestionRecord record = existing.get();
            publisher.publish(BookDownloadedEvent.of(bookId, record.headerPath(), record.bodyPath(), record.ingestedAt()));
            String path = toPrefixPath(record.headerPath(), bookId);
            return new IngestResult(bookId, IngestResult.STATUS_ALREADY_AVAILABLE, path);
        }

        try {
            StoredBook stored = downloader.download(bookId);
            String headerPath = stored.headerFile().toAbsolutePath().normalize().toString();
            String bodyPath = stored.bodyFile().toAbsolutePath().normalize().toString();
            String prefixPath = stored.directory().resolve(String.valueOf(bookId)).toAbsolutePath().normalize().toString();

            store.recordAvailable(bookId, headerPath, bodyPath, stored.ingestedAt());

            publisher.publish(BookDownloadedEvent.of(bookId, headerPath, bodyPath, stored.ingestedAt()));

            return new IngestResult(bookId, IngestResult.STATUS_DOWNLOADED, prefixPath);
        } catch (DownloadException e) {
            LOG.warn("Failed to download book {}: {}", bookId, e.getMessage());
            store.recordFailed(bookId, Instant.now());
            throw e;
        }
    }

    /** Returns the ingestion status for {@code bookId} if present in the log. */
    public Optional<String> getStatus(int bookId) throws SQLException {
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be a positive integer but was " + bookId);
        }
        return store.findById(bookId).map(IngestionRecord::status);
    }

    /** Returns the list of all book IDs currently marked as {@code 'available'}. */
    public List<Integer> listAvailable() throws SQLException {
        return store.listAvailable();
    }

    private static String toPrefixPath(String headerPath, int bookId) {
        if (headerPath != null && headerPath.endsWith(".header.txt")) {
            return headerPath.substring(0, headerPath.length() - ".header.txt".length());
        }
        return "datalake/" + bookId;
    }
}

