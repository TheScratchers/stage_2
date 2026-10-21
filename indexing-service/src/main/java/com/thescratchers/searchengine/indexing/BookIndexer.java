package com.thescratchers.searchengine.indexing;

import com.thescratchers.searchengine.common.BookRecord;
import com.thescratchers.searchengine.common.TextTokenizer;
import com.thescratchers.searchengine.indexing.metadata.HeaderParser;
import com.thescratchers.searchengine.indexing.metadata.ParsedHeader;
import com.thescratchers.searchengine.indexing.store.DatamartStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexes one book of the datalake: parses its header, counts the terms of its body and stores
 * both in the datamart in a single transaction.
 *
 * <p>The caller supplies the file paths (from the ingestion event or the configuration); this class
 * never builds datalake paths itself. It keeps no mutable state, so one instance can be used by
 * several threads.
 */
public final class BookIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(BookIndexer.class);

    private final DatamartStore store;

    /**
     * Summary of an indexed book.
     *
     * @param bookId        Project Gutenberg id
     * @param distinctTerms number of different terms, i.e. postings stored for the book
     * @param totalTokens   number of tokens in the body
     */
    public record IndexResult(int bookId, int distinctTerms, int totalTokens) {
    }

    /** Creates an indexer that writes to {@code store}. */
    public BookIndexer(DatamartStore store) {
        this.store = store;
    }

    /**
     * Indexes a book, replacing any previous version of it in the datamart.
     *
     * @param bookId     Project Gutenberg id
     * @param headerFile header file of the book in the datalake
     * @param bodyFile   body file of the book in the datalake
     * @return the number of distinct terms and tokens indexed
     * @throws IOException  if a file is missing or cannot be read
     * @throws SQLException if the datamart transaction fails (nothing of the book is stored)
     */
    public IndexResult index(int bookId, Path headerFile, Path bodyFile) throws IOException, SQLException {
        requireFile(headerFile, "Header");
        requireFile(bodyFile, "Body");

        ParsedHeader header = HeaderParser.parse(Files.readString(headerFile, StandardCharsets.UTF_8));
        List<String> tokens = TextTokenizer.tokenize(Files.readString(bodyFile, StandardCharsets.UTF_8));
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : tokens) {
            frequencies.merge(token, 1, Integer::sum);
        }

        BookRecord book = new BookRecord(bookId, header.title(), header.author(), header.language(), header.year());
        store.indexBook(book, frequencies);

        IndexResult result = new IndexResult(bookId, frequencies.size(), tokens.size());
        LOG.info("Indexed book {} ({} distinct terms, {} tokens)", bookId, result.distinctTerms(), result.totalTokens());
        return result;
    }

    private static void requireFile(Path file, String kind) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(kind + " file not found: " + file.toAbsolutePath());
        }
    }
}
