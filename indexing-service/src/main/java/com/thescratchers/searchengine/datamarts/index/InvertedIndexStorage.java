package com.thescratchers.searchengine.datamarts.index;

import java.util.List;
import java.util.Map;

public interface InvertedIndexStorage {
    void save(String term, int bookId);
    void build(Map<String, List<Integer>> memoryIndex);
    List<Integer> search(String term);
}