package com.thescratchers.searchengine.datamarts.index;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FolderIndexStorage implements InvertedIndexStorage {

    private static final String DEFAULT_ROOT = "data/datamarts/index/";

    private final String rootPath;

    public FolderIndexStorage() {
        this(DEFAULT_ROOT);
    }

    public FolderIndexStorage(String rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    public void save(String term, int bookId) {
    }

    @Override
    public List<Integer> search(String term) {
        return Collections.emptyList();
    }

    @Override
    public void build(Map<String, List<Integer>> memoryIndex) {
    }
}