package com.thescratchers.searchengine.ingestion.datalake;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * Directory layouts of the datalake, the three structures compared in Stage 1.
 *
 * <ul>
 *   <li>{@link #TIME_BASED} (default): {@code <base>/yyyyMMdd/HH/}, using the UTC ingestion time</li>
 *   <li>{@link #BOOK_BASED}: {@code <base>/<bookId>/}</li>
 *   <li>{@link #BATCH_BASED}: {@code <base>/batch_<start>-<end>/}, in batches of {@value #BATCH_SIZE} ids</li>
 * </ul>
 *
 * <p>In every layout a book is stored as {@code <id>.header.txt} and {@code <id>.body.txt} inside
 * its directory. The layout is selected with the {@code DATALAKE_LAYOUT} setting.
 */
public enum DatalakeLayout {

    TIME_BASED("time_based") {
        @Override
        public Path directoryFor(Path base, int bookId, Instant ingestedAt) {
            ZonedDateTime utc = ingestedAt.atZone(ZoneOffset.UTC);
            return base.resolve(utc.format(DATE_FORMAT)).resolve(utc.format(HOUR_FORMAT));
        }
    },

    BOOK_BASED("book_based") {
        @Override
        public Path directoryFor(Path base, int bookId, Instant ingestedAt) {
            return base.resolve(String.valueOf(bookId));
        }
    },

    BATCH_BASED("batch_based") {
        @Override
        public Path directoryFor(Path base, int bookId, Instant ingestedAt) {
            int start = (bookId / BATCH_SIZE) * BATCH_SIZE;
            return base.resolve("batch_" + start + "-" + (start + BATCH_SIZE - 1));
        }
    };

    /** Number of consecutive ids grouped in one directory by {@link #BATCH_BASED}. */
    public static final int BATCH_SIZE = 1000;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("HH");

    private final String label;

    DatalakeLayout(String label) {
        this.label = label;
    }

    /** Configuration name of the layout ("time_based", "book_based" or "batch_based"). */
    public String label() {
        return label;
    }

    /** Directory under {@code base} where the book is stored. Only TIME_BASED uses {@code ingestedAt}. */
    public abstract Path directoryFor(Path base, int bookId, Instant ingestedAt);

    /** Header file of {@code bookId} inside {@code directory}. */
    public static Path headerFile(Path directory, int bookId) {
        return directory.resolve(bookId + ".header.txt");
    }

    /** Body file of {@code bookId} inside {@code directory}. */
    public static Path bodyFile(Path directory, int bookId) {
        return directory.resolve(bookId + ".body.txt");
    }

    /**
     * Returns the layout with the given configuration name (case-insensitive).
     *
     * @throws IllegalArgumentException if no layout has that name
     */
    public static DatalakeLayout fromLabel(String label) {
        String normalized = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(layout -> layout.label.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown datalake layout '" + label + "'; expected time_based, book_based or batch_based"));
    }
}
