package com.thescratchers.searchengine.datamarts.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class JsonHierarchicalIndexStorage implements InvertedIndexStorage {
    private final String basePath = "data/datamarts/hierarchical/";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void save(String term, int bookId) {
        if (term == null || term.isEmpty()) return;
        try {
            String prefix = term.substring(0, 1).toLowerCase();
            File dir = new File(basePath + prefix);
            dir.mkdirs();
            File file = new File(dir, "data.json");
            
            Map<String, List<Integer>> index;
            if (file.exists()) {
                index = mapper.readValue(file, new TypeReference<Map<String, List<Integer>>>() {});
            } else {
                index = new HashMap<>();
            }
            
            index.computeIfAbsent(term, k -> new ArrayList<>()).add(bookId);
            
            List<Integer> uniqueBooks = new ArrayList<>(new HashSet<>(index.get(term)));
            index.put(term, uniqueBooks);
            
            mapper.writeValue(file, index);
        } catch (IOException e) {
            throw new RuntimeException("Error incrementally saving hierarchical JSON", e);
        }
    }

    @Override
    public void build(Map<String, List<Integer>> memoryIndex) {
        Map<String, Map<String, List<Integer>>> grouped = new HashMap<>();
        
        for (Map.Entry<String, List<Integer>> entry : memoryIndex.entrySet()) {
            String term = entry.getKey();
            if (term.isEmpty()) continue;
            String prefix = term.substring(0, 1).toLowerCase();
            grouped.computeIfAbsent(prefix, k -> new HashMap<>()).put(term, entry.getValue());
        }
        
        for (Map.Entry<String, Map<String, List<Integer>>> group : grouped.entrySet()) {
            try {
                File dir = new File(basePath + group.getKey());
                dir.mkdirs();
                mapper.writeValue(new File(dir, "data.json"), group.getValue());
            } catch (IOException e) {
                throw new RuntimeException("Error writing hierarchical JSON", e);
            }
        }
    }

    @Override
    public List<Integer> search(String term) {
        if (term == null || term.isEmpty()) return Collections.emptyList();
        String prefix = term.substring(0, 1).toLowerCase();
        File file = new File(basePath + prefix + "/data.json");
        
        if (!file.exists()) return Collections.emptyList();
        
        try {
            Map<String, List<Integer>> index = mapper.readValue(file, new TypeReference<Map<String, List<Integer>>>() {});
            return index.getOrDefault(term, Collections.emptyList());
        } catch (IOException e) {
            throw new RuntimeException("Error reading hierarchical JSON", e);
        }
    }
}