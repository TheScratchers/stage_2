package com.thescratchers.searchengine.apitests.pipeline;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal stand-in for Project Gutenberg that serves the bundled corpus of {@code samples/books}
 * at {@code /cache/epub/{id}/pg{id}.txt}, rebuilding each raw text as header, START marker, body,
 * END marker and license. Unknown books answer {@code 404}, as Gutenberg does.
 *
 * <p>It makes the pipeline checks reproducible offline: the expected results do not change if
 * Gutenberg updates a book. The Ingestion Service is pointed at it through {@code GUTENBERG_BASE_URL}.
 */
public final class LocalGutenberg implements AutoCloseable {

    private static final Pattern BOOK_PATH = Pattern.compile("/cache/epub/(\\d+)/pg(\\d+)\\.txt");

    private final Path booksDir;
    private final HttpServer server;

    /** Starts serving {@code booksDir} on a free local port. */
    public LocalGutenberg(Path booksDir) throws IOException {
        this.booksDir = booksDir;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** Base URL to use as {@code GUTENBERG_BASE_URL}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Matcher matcher = BOOK_PATH.matcher(exchange.getRequestURI().getPath());
        byte[] body = null;
        if (matcher.matches() && matcher.group(1).equals(matcher.group(2))) {
            body = rawText(matcher.group(1));
        }
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private byte[] rawText(String bookId) throws IOException {
        Path header = booksDir.resolve(bookId + ".header.txt");
        Path body = booksDir.resolve(bookId + ".body.txt");
        if (!Files.isRegularFile(header) || !Files.isRegularFile(body)) {
            return null;
        }
        String text = Files.readString(header, StandardCharsets.UTF_8) + "\r\n\r\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK " + bookId + " ***\r\n\r\n"
                + Files.readString(body, StandardCharsets.UTF_8) + "\r\n\r\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK " + bookId + " ***\r\n\r\n"
                + "Updated editions will replace the previous one.\r\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
