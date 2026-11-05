package com.thescratchers.searchengine.indexing.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The fixed corpus of the project ({@code samples/books.txt}, team guide 5.9), as the benchmarks
 * see it. Paths are relative to the module directory, where Maven runs the benchmarks.
 */
final class Corpus {

    /** Directory with the header and body of every corpus book. */
    static final Path BOOKS_DIR = Path.of("..", "samples", "books");

    private static final Path BOOK_LIST = Path.of("..", "samples", "books.txt");

    private Corpus() {
    }

    /** Ids listed in {@code samples/books.txt}, in file order; comments and blank lines are skipped. */
    static List<Integer> bookIds() throws IOException {
        return Files.readAllLines(BOOK_LIST, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(Integer::parseInt)
                .toList();
    }

    /** Header file of a corpus book. */
    static Path header(int bookId) {
        return BOOKS_DIR.resolve(bookId + ".header.txt");
    }

    /** Body file of a corpus book. */
    static Path body(int bookId) {
        return BOOKS_DIR.resolve(bookId + ".body.txt");
    }

    /** Bytes of the header and body files of the given books. */
    static long sizeBytes(List<Integer> bookIds) throws IOException {
        long bytes = 0;
        for (int bookId : bookIds) {
            bytes += Files.size(header(bookId)) + Files.size(body(bookId));
        }
        return bytes;
    }
}
