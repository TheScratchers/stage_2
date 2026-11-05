package com.thescratchers.searchengine.indexing.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A temporary directory for a benchmark datamart, deleted with everything inside it (the database
 * and its WAL files) on {@link #close()}. Nothing is ever written under {@code data/}.
 *
 * @param path the directory
 */
record TempDirectory(Path path) implements AutoCloseable {

    /** Creates a new, empty temporary directory. */
    static TempDirectory create() throws IOException {
        return new TempDirectory(Files.createTempDirectory("indexing-benchmark"));
    }

    /** {@code datamart.db} inside this directory. */
    Path datamart() {
        return path.resolve("datamart.db");
    }

    /** Deletes the directory and its content. */
    @Override
    public void close() throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
