package com.thescratchers.searchengine.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads the shared cross-language dataset defined in shared/CONTRACT.md:
 * the 20 real books listed in shared/books.txt, read from the Java datalake
 * (java/data/datalake). Every benchmark replicates these 20 books across the
 * synthetic scales, so Java stays comparable with Python and C++.
 *
 * All paths are relative to the java/ directory (run the benchmarks from there).
 */
final class ContractDataset {

    static final Path BOOKS_PATH = Paths.get("../shared/books.txt");
    static final Path DATALAKE_ROOT = Paths.get("data/datalake");

    /** Raw header and body text of one real book. */
    record RealBook(String header, String body) {}

    private ContractDataset() {}

    /** The 20 contract book ids, in books.txt's listed order. */
    static List<Integer> loadBookIds() throws IOException {
        if (!Files.exists(BOOKS_PATH)) {
            throw new IllegalStateException("Contract file not found: " + BOOKS_PATH.toAbsolutePath()
                    + " (run the benchmarks from the java/ directory)");
        }
        List<Integer> ids = new ArrayList<>();
        for (String line : Files.readAllLines(BOOKS_PATH)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            ids.add(Integer.parseInt(trimmed));
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("No book ids found in " + BOOKS_PATH.toAbsolutePath());
        }
        return ids;
    }

    /** One walk of the datalake: book id -> file, for files named {@code <id><suffix>}. */
    static Map<Integer, Path> findFiles(String suffix) throws IOException {
        Map<Integer, Path> found = new HashMap<>();
        if (!Files.exists(DATALAKE_ROOT)) return found;
        try (Stream<Path> stream = Files.walk(DATALAKE_ROOT)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.endsWith(suffix)) return;
                try {
                    int id = Integer.parseInt(name.substring(0, name.length() - suffix.length()));
                    found.putIfAbsent(id, p);
                } catch (NumberFormatException ignored) {
                    // not a "<id><suffix>" file
                }
            });
        }
        return found;
    }

    /**
     * Header and body of the 20 real books. Fails fast if any is missing:
     * a missing book would silently shrink the 20-book replication cycle
     * (CONTRACT.md section 4.1) and make the results incomparable.
     */
    static Map<Integer, RealBook> loadRealBooks() throws IOException {
        List<Integer> ids = loadBookIds();
        Map<Integer, Path> bodies = findFiles(".body.txt");
        Map<Integer, Path> headers = findFiles(".header.txt");

        List<Integer> missing = new ArrayList<>();
        for (int id : ids) {
            if (!bodies.containsKey(id) || !headers.containsKey(id)) missing.add(id);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing real books in " + DATALAKE_ROOT.toAbsolutePath()
                    + " for ids " + missing + " - run DownloadContractBooks (or download all "
                    + ids.size() + " books from shared/books.txt) before running the benchmark.");
        }

        Map<Integer, RealBook> books = new LinkedHashMap<>();
        for (int id : ids) {
            books.put(id, new RealBook(Files.readString(headers.get(id)), Files.readString(bodies.get(id))));
        }
        return books;
    }
}
