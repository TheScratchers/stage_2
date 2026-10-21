package com.thescratchers.searchengine.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Access to the bundled corpus in {@code samples/books} and raw Gutenberg texts rebuilt from it. */
public final class SampleBooks {

    /** {@code samples/books}, relative to the module directory where Maven runs the tests. */
    public static final Path DIR = Path.of("..", "samples", "books");

    private SampleBooks() {
    }

    public static String header(int bookId) throws IOException {
        return Files.readString(DIR.resolve(bookId + ".header.txt"), StandardCharsets.UTF_8);
    }

    public static String body(int bookId) throws IOException {
        return Files.readString(DIR.resolve(bookId + ".body.txt"), StandardCharsets.UTF_8);
    }

    /** Raw text as served by Gutenberg: header, START marker, body, END marker and license. */
    public static String rawText(int bookId) throws IOException {
        return header(bookId) + "\r\n\r\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK SAMPLE " + bookId + " ***\r\n\r\n"
                + body(bookId) + "\r\n\r\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK SAMPLE " + bookId + " ***\r\n\r\n"
                + "Updated editions will replace the previous one. (license text...)\r\n";
    }
}
