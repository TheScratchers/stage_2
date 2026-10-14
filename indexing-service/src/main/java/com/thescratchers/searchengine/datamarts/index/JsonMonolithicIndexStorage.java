package com.thescratchers.searchengine.datamarts.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class JsonMonolithicIndexStorage implements InvertedIndexStorage {
    private final String filePath = "data/datamarts/inverted_index.json";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void save(String term, int bookId) {
        try {
            File file = new File(filePath);
            Map<String, List<Integer>> index;
            if (file.exists()) {
                index = mapper.readValue(file, new TypeReference<Map<String, List<Integer>>>() {});
            } else {
                index = new HashMap<>();
                file.getParentFile().mkdirs();
            }
            
            index.computeIfAbsent(term, k -> new ArrayList<>()).add(bookId);

            List<Integer> uniqueBooks = new ArrayList<>(new HashSet<>(index.get(term)));
            index.put(term, uniqueBooks);
            
            mapper.writeValue(file, index);
        } catch (IOException e) {
            throw new RuntimeException("Error incrementally saving monolithic JSON", e);
        }
    }

    @Override
    public void build(Map<String, List<Integer>> memoryIndex) {
        try {
            File file = new File(filePath);
            file.getParentFile().mkdirs();
            mapper.writeValue(file, memoryIndex);
        } catch (IOException e) {
            throw new RuntimeException("Error writing monolithic JSON", e);
        }
    }

    @Override
    public List<Integer> search(String term) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return Collections.emptyList();
            Map<String, List<Integer>> index = mapper.readValue(file, new TypeReference<Map<String, List<Integer>>>() {});
            return index.getOrDefault(term, Collections.emptyList());
        } catch (IOException e) {
            throw new RuntimeException("Error reading monolithic JSON", e);
        }
    }
}