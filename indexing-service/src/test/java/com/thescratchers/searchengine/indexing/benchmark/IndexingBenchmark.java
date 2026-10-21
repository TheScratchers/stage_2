package com.thescratchers.searchengine.indexing.benchmark;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.common.TextTokenizer;
import com.thescratchers.searchengine.indexing.metadata.HeaderParser;
import com.thescratchers.searchengine.indexing.metadata.ParsedHeader;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Microbenchmarks of the indexing pipeline on book 1342 (Pride and Prejudice) of the bundled
 * corpus: tokenizing the body, parsing the header and storing the book in the datamart.
 *
 * <p>Run from the repository root with:
 * <pre>
 * mvn -q install -DskipTests
 * mvn -pl indexing-service -Pjmh test-compile exec:exec
 * </pre>
 * The datamart is written to a temporary directory created for each iteration and deleted after
 * it; nothing is written under {@code data/}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class IndexingBenchmark {

    private static final int BOOK_ID = 1342;
    private static final Path SAMPLES = Path.of("..", "samples", "books");

    /** Book 1342 loaded once per trial, plus the inputs of {@link #indexBook}. */
    @State(Scope.Benchmark)
    public static class BookState {

        String header;
        String body;
        BookRecord record;
        Map<String, Integer> termFrequencies;

        /** Reads the sample book and precomputes its metadata and term frequencies. */
        @Setup(Level.Trial)
        public void load() throws IOException {
            header = Files.readString(SAMPLES.resolve(BOOK_ID + ".header.txt"), StandardCharsets.UTF_8);
            body = Files.readString(SAMPLES.resolve(BOOK_ID + ".body.txt"), StandardCharsets.UTF_8);
            ParsedHeader parsed = HeaderParser.parse(header);
            record = new BookRecord(BOOK_ID, parsed.title(), parsed.author(), parsed.language(), parsed.year());
            termFrequencies = new HashMap<>();
            for (String token : TextTokenizer.tokenize(body)) {
                termFrequencies.merge(token, 1, Integer::sum);
            }
        }
    }

    /** A fresh datamart in a temporary directory, recreated for every iteration. */
    @State(Scope.Benchmark)
    public static class DatamartState {

        Path dir;
        DatamartStore store;

        /** Creates the temporary directory and the datamart schema. */
        @Setup(Level.Iteration)
        public void open() throws IOException, SQLException {
            dir = Files.createTempDirectory("indexing-benchmark");
            store = new DatamartStore(dir.resolve("datamart.db"));
        }

        /** Deletes the temporary directory with the database and its WAL files. */
        @TearDown(Level.Iteration)
        public void delete() throws IOException {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /** Tokenizes the whole body of the book. */
    @Benchmark
    public List<String> tokenize(BookState book) {
        return TextTokenizer.tokenize(book.body);
    }

    /** Parses the Gutenberg header of the book. */
    @Benchmark
    public ParsedHeader parseHeader(BookState book) {
        return HeaderParser.parse(book.header);
    }

    /**
     * Stores the book and its postings in one transaction. After the first call every invocation
     * re-indexes the same book, which replaces its postings. The result is written to the database,
     * so the JIT cannot eliminate the call.
     */
    @Benchmark
    public void indexBook(BookState book, DatamartState datamart) throws SQLException {
        datamart.store.indexBook(book.record, book.termFrequencies);
    }
}
