package com.thescratchers.searchengine.datalake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public class Downloader {

    private static final String GUTENBERG_URL_TEMPLATE =
            "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    private static final String START_MARKER =
            "*** START OF THE PROJECT GUTENBERG EBOOK";

    private static final String END_MARKER =
            "*** END OF THE PROJECT GUTENBERG EBOOK";

    private static final String DATALAKE_ROOT = "data/datalake/";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final DateTimeFormatter HOUR_FORMAT =
            DateTimeFormatter.ofPattern("HH");

    public boolean downloadBook(int bookId) {
        String url = String.format(GUTENBERG_URL_TEMPLATE, bookId, bookId);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            System.out.println("[DOWNLOADER] Fetching book " + bookId + " from: " + url);

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[DOWNLOADER] HTTP " + response.statusCode()
                        + " for book " + bookId);
                return false;
            }

            String rawText = response.body();

            if (!rawText.contains(START_MARKER) || !rawText.contains(END_MARKER)) {
                System.err.println("[DOWNLOADER] Gutenberg markers not found for book " + bookId);
                return false;
            }

            String[] beforeAndAfterStart =
                    rawText.split(Pattern.quote(START_MARKER), 2);
            String header = beforeAndAfterStart[0].trim();

            String[] bodyAndFooter =
                    beforeAndAfterStart[1].split(Pattern.quote(END_MARKER), 2);
            String body = bodyAndFooter[0].trim();

            LocalDateTime now = LocalDateTime.now();
            Path outputDir = Paths.get(
                    DATALAKE_ROOT,
                    now.format(DATE_FORMAT),
                    now.format(HOUR_FORMAT));

            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve(bookId + ".header.txt"), header);
            Files.writeString(outputDir.resolve(bookId + ".body.txt"), body);

            System.out.println("[DOWNLOADER] Book " + bookId
                    + " saved to: " + outputDir.toAbsolutePath());
            return true;

        } catch (Exception e) {
            System.err.println("[DOWNLOADER] Failed to download book " + bookId
                    + ": " + e.getMessage());
            return false;
        }
    }
}