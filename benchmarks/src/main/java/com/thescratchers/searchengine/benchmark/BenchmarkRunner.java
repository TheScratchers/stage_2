package com.thescratchers.searchengine.benchmark;

import com.thescratchers.searchengine.datamarts.TextTokenizer;
import com.thescratchers.searchengine.datamarts.index.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
public class BenchmarkRunner {

    private static final Path BOOKS_PATH = Paths.get("../shared/books.txt");
    private static final Path WORDS_PATH = Paths.get("../shared/words.txt");
    private static final Path DATALAKE_ROOT = Paths.get("data/datalake");

    @State(Scope.Thread)
    public static class IndexingState {
        @Param({"100", "1000", "10000"})
        public int scale;

        public Map<String, List<Integer>> memoryIndex;
        public SqliteIndexStorage sqliteStorage;
        public JsonMonolithicIndexStorage monolithicStorage;
        public JsonHierarchicalIndexStorage hierarchicalStorage;

        @Setup(Level.Iteration)
        public void setUp() throws IOException {
            cleanDirectory(new File("data/datamarts"));
            new File("data/datamarts").mkdirs();

            memoryIndex = generateMemoryIndex(scale);

            sqliteStorage = new SqliteIndexStorage();
            monolithicStorage = new JsonMonolithicIndexStorage();
            hierarchicalStorage = new JsonHierarchicalIndexStorage();
        }
    }

    @State(Scope.Thread)
    public static class QueryingState {
        @Param({"100", "1000", "10000"})
        public int scale;

        public SqliteIndexStorage sqliteStorage;
        public JsonMonolithicIndexStorage monolithicStorage;
        public JsonHierarchicalIndexStorage hierarchicalStorage;
        public List<String> queryWords;

        @Setup(Level.Trial)
        public void setUp() throws IOException {
            cleanDirectory(new File("data/datamarts"));
            new File("data/datamarts").mkdirs();

            Map<String, List<Integer>> memoryIndex = generateMemoryIndex(scale);

            sqliteStorage = new SqliteIndexStorage();
            monolithicStorage = new JsonMonolithicIndexStorage();
            hierarchicalStorage = new JsonHierarchicalIndexStorage();

            sqliteStorage.build(memoryIndex);
            monolithicStorage.build(memoryIndex);
            hierarchicalStorage.build(memoryIndex);

            queryWords = loadLines(WORDS_PATH);
        }
    }

    private static void cleanDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        cleanDirectory(file);
                    }
                    file.delete();
                }
            }
        }
    }

    private static Path findFile(int bookId) {
        if (!Files.exists(DATALAKE_ROOT)) return null;
        try (Stream<Path> stream = Files.walk(DATALAKE_ROOT)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(bookId + ".body.txt"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> loadLines(Path path) throws IOException {
        List<String> result = new ArrayList<>();
        if (!Files.exists(path)) return result;
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            result.add(trimmed);
        }
        return result;
    }

    private static Map<String, List<Integer>> generateMemoryIndex(int scale) throws IOException {
        List<String> bookIdLines = loadLines(BOOKS_PATH);
        if (bookIdLines.isEmpty()) {
            throw new IllegalStateException("No book ids found in " + BOOKS_PATH.toAbsolutePath());
        }

        // Real books, in books.txt's listed order. Tokenized with the
        // contract tokenizer (CONTRACT.md Section 2): lowercase, then
        // maximal runs of [A-Za-z] - exactly what TextTokenizer does.
        List<Set<String>> realBooksTokens = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();

        for (String line : bookIdLines) {
            int bookId = Integer.parseInt(line);
            Path bodyFile = findFile(bookId);
            if (bodyFile == null) {
                missing.add(bookId);
                continue;
            }
            String content = Files.readString(bodyFile);
            realBooksTokens.add(new HashSet<>(TextTokenizer.tokenize(content)));
        }

        // CONTRACT.md Section 4.1 (residual risk): a missing book would
        // silently shrink the 20-book cycle and break comparability with
        // Python and C++, so fail loudly instead of benchmarking a
        // different corpus.
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing real books in " + DATALAKE_ROOT.toAbsolutePath()
                    + " for ids " + missing + " - download all " + bookIdLines.size()
                    + " books from shared/books.txt before running the benchmark.");
        }

        int booksCount = realBooksTokens.size();
        Map<String, List<Integer>> memoryIndex = new HashMap<>();

        // CONTRACT.md Section 4.1: the i-th synthetic book (0-indexed
        // POSITION) replicates the real book at books.txt position
        // i mod 20. The synthetic book id is position + 1 (ids 1..scale);
        // the id plays no role in choosing the content.
        for (int position = 0; position < scale; position++) {
            int syntheticId = position + 1;
            Set<String> tokens = realBooksTokens.get(position % booksCount);
            for (String token : tokens) {
                memoryIndex.computeIfAbsent(token, k -> new ArrayList<>()).add(syntheticId);
            }
        }

        return memoryIndex;
    }

    @Benchmark
    public void measureIndexingSqlite(IndexingState state) {
        state.sqliteStorage.build(state.memoryIndex);
    }

    @Benchmark
    public void measureIndexingJsonMonolithic(IndexingState state) {
        state.monolithicStorage.build(state.memoryIndex);
    }

    @Benchmark
    public void measureIndexingJsonHierarchical(IndexingState state) {
        state.hierarchicalStorage.build(state.memoryIndex);
    }

    @Benchmark
    public void measureQueryingSqlite(QueryingState state, Blackhole blackhole) {
        for (String word : state.queryWords) {
            blackhole.consume(state.sqliteStorage.search(word));
        }
    }

    @Benchmark
    public void measureQueryingJsonMonolithic(QueryingState state, Blackhole blackhole) {
        for (String word : state.queryWords) {
            blackhole.consume(state.monolithicStorage.search(word));
        }
    }

    @Benchmark
    public void measureQueryingJsonHierarchical(QueryingState state, Blackhole blackhole) {
        for (String word : state.queryWords) {
            blackhole.consume(state.hierarchicalStorage.search(word));
        }
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(BenchmarkRunner.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON)
                .result("results.json")
                .build();
        new Runner(opt).run();
    }
}