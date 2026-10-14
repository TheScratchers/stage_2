package com.thescratchers.searchengine.datalake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The three datalake directory layouts compared in the Stage 1 benchmark.
 * Same layouts as the Python implementation (datalake.py, datalake_book.py,
 * datalake_batch.py), so results are comparable across languages:
 *
 * <ul>
 *   <li>TIME_BASED  : {@code <base>/yyyyMMdd/HH/}</li>
 *   <li>BOOK_BASED  : {@code <base>/<bookId>/}</li>
 *   <li>BATCH_BASED : {@code <base>/batch_<start>-<end>/} (batches of 1000 ids)</li>
 * </ul>
 *
 * In every layout a book is stored as {@code <id>.header.txt} and
 * {@code <id>.body.txt} inside its directory.
 */
public enum DatalakeLayout {

    TIME_BASED("time_based") {
        @Override
        public Path directoryFor(Path base, int bookId, LocalDateTime ingestedAt) {
            return base.resolve(ingestedAt.format(DATE_FORMAT))
                       .resolve(ingestedAt.format(HOUR_FORMAT));
        }
    },

    BOOK_BASED("book_based") {
        @Override
        public Path directoryFor(Path base, int bookId, LocalDateTime ingestedAt) {
            return base.resolve(String.valueOf(bookId));
        }
    },

    BATCH_BASED("batch_based") {
        @Override
        public Path directoryFor(Path base, int bookId, LocalDateTime ingestedAt) {
            int start = (bookId / BATCH_SIZE) * BATCH_SIZE;
            int end = start + BATCH_SIZE - 1;
            return base.resolve("batch_" + start + "-" + end);
        }
    };

    public static final int BATCH_SIZE = 1000;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH");

    private final String label;

    DatalakeLayout(String label) {
        this.label = label;
    }

    /** Name used in the benchmark output ("time_based", "book_based", "batch_based"). */
    public String label() {
        return label;
    }

    /**
     * Directory where the given book lives under {@code base}.
     * {@code ingestedAt} is only used by TIME_BASED (the other layouts ignore it).
     */
    public abstract Path directoryFor(Path base, int bookId, LocalDateTime ingestedAt);

    public static Path bodyFile(Path directory, int bookId) {
        return directory.resolve(bookId + ".body.txt");
    }

    public static Path headerFile(Path directory, int bookId) {
        return directory.resolve(bookId + ".header.txt");
    }

    /**
     * Writes a book into {@code directory} (created if missing) using the
     * {@code <id>.header.txt} / {@code <id>.body.txt} naming convention.
     * Header and body are stripped, as the Downloader / Python save_book do.
     */
    public static void saveBook(Path directory, int bookId, String header, String body)
            throws IOException {
        Files.createDirectories(directory);
        Files.writeString(bodyFile(directory, bookId), body.strip());
        Files.writeString(headerFile(directory, bookId), header.strip());
    }
}
