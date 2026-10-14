package com.thescratchers.searchengine.index;

import com.thescratchers.searchengine.datamarts.TextTokenizer;
import com.thescratchers.searchengine.datamarts.index.InvertedIndexStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InvertedIndexBuilder {

    private final InvertedIndexStorage storage;

    public InvertedIndexBuilder(InvertedIndexStorage storage) {
        this.storage = storage;
    }

    public void buildIndexForBook(int bookId, String bodyFilePath) throws IOException {
        Path bodyPath = Paths.get(bodyFilePath);
        if (!Files.exists(bodyPath)) {
            throw new IOException("Body file not found: " + bodyPath.toAbsolutePath());
        }

        String content        = Files.readString(bodyPath);
        List<String> tokens   = TextTokenizer.tokenize(content);
        Set<String> uniqueTerms = new HashSet<>(tokens);
        for (String term : uniqueTerms) {
            storage.save(term, bookId);
        }
    }

    public List<Integer> search(String term) {
        return storage.search(term.toLowerCase());
    }
}