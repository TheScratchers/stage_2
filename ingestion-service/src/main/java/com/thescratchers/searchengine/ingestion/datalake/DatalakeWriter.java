package com.thescratchers.searchengine.ingestion.datalake;

import com.thescratchers.searchengine.common.Config;
import com.thescratchers.searchengine.ingestion.gutenberg.SplitBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Writes books into the datalake so that a file is either absent or complete, never half-written.
 *
 * <p>Each file is first written to a temporary file in the same directory and then renamed to its
 * final name with an atomic move, so a crash in the middle of a write leaves at most a stray
 * {@code .tmp} file and never a truncated {@code .header.txt} or {@code .body.txt}. Storing the
 * same book again replaces its files. Whether a stored book is ready for indexing is recorded
 * separately by the ingestion service once this call has returned.
 */
public final class DatalakeWriter {

    /** Setting that selects the {@link DatalakeLayout} (default {@code time_based}). */
    public static final String LAYOUT_KEY = "DATALAKE_LAYOUT";

    private final Path root;
    private final DatalakeLayout layout;

    public DatalakeWriter(Path root, DatalakeLayout layout) {
        this.root = root;
        this.layout = layout;
    }

    /** Writer over {@code Config.datalakeDir()} with the layout from {@code DATALAKE_LAYOUT}. */
    public static DatalakeWriter fromConfig() {
        return new DatalakeWriter(Config.datalakeDir(),
                DatalakeLayout.fromLabel(Config.get(LAYOUT_KEY, DatalakeLayout.TIME_BASED.label())));
    }

    /** Stores the header and body of {@code bookId}, creating the directories it needs. */
    public StoredBook store(int bookId, SplitBook book, Instant ingestedAt) throws IOException {
        Path directory = layout.directoryFor(root, bookId, ingestedAt);
        Files.createDirectories(directory);
        Path header = DatalakeLayout.headerFile(directory, bookId);
        Path body = DatalakeLayout.bodyFile(directory, bookId);
        writeAtomically(body, book.body());
        writeAtomically(header, book.header());
        return new StoredBook(bookId, directory, header, body, ingestedAt);
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
