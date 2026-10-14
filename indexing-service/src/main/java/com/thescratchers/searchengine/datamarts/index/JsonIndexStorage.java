package com.thescratchers.searchengine.datamarts.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class JsonIndexStorage implements InvertedIndexStorage {

    private static final String DEFAULT_PATH = "data/datamarts/inverted_index.json";

    private final Path outputPath;

    public JsonIndexStorage() {
        this(Paths.get(DEFAULT_PATH));
    }

    public JsonIndexStorage(Path outputPath) {
        this.outputPath = outputPath;
    }

    @Override
    public void save(String term, int bookId) {
        if (term == null || term.isEmpty()) return;
        try {
            Files.createDirectories(outputPath.getParent());
            Map<String, List<Integer>> index = loadExisting();
            index.computeIfAbsent(term, k -> new ArrayList<>());
            if (!index.get(term).contains(bookId)) {
                index.get(term).add(bookId);
            }
            writeJson(new TreeMap<>(index));
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }

    @Override
    public List<Integer> search(String term) {
        if (!Files.exists(outputPath)) return Collections.emptyList();
        try {
            String content = Files.readString(outputPath);
            String key = "\"" + term.toLowerCase() + "\": [";
            int start = content.indexOf(key);
            if (start == -1) return Collections.emptyList();
            int arrayStart = content.indexOf('[', start) + 1;
            int arrayEnd   = content.indexOf(']', arrayStart);
            String raw = content.substring(arrayStart, arrayEnd).trim();
            if (raw.isEmpty()) return Collections.emptyList();
            List<Integer> result = new ArrayList<>();
            for (String part : raw.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) result.add(Integer.parseInt(t));
            }
            return Collections.unmodifiableList(result);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, List<Integer>> loadExisting() throws IOException {
        Map<String, List<Integer>> index = new HashMap<>();
        if (!Files.exists(outputPath)) return index;
        String content = Files.readString(outputPath).trim();
        if (content.isEmpty() || content.equals("{}")) return index;
        String inner = content.substring(1, content.length() - 1).trim();
        for (String entry : inner.split(",\n")) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            int colon     = entry.indexOf(':');
            String term   = entry.substring(0, colon).trim().replace("\"", "");
            String arrRaw = entry.substring(colon + 1).trim();
            arrRaw = arrRaw.substring(1, arrRaw.length() - 1).trim();
            List<Integer> ids = new ArrayList<>();
            if (!arrRaw.isEmpty()) {
                for (String id : arrRaw.split(",")) {
                    ids.add(Integer.parseInt(id.trim()));
                }
            }
            index.put(term, ids);
        }
        return index;
    }

    private void writeJson(Map<String, List<Integer>> index) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        int count = 0;
        for (Map.Entry<String, List<Integer>> e : index.entrySet()) {
            if (count++ > 0) sb.append(",\n");
            sb.append("  \"").append(e.getKey()).append("\": [");
            List<Integer> ids = e.getValue();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(ids.get(i));
            }
            sb.append("]");
        }
        sb.append("\n}");
        Files.writeString(outputPath, sb.toString());
    }
    
    @Override
    public void build(Map<String, List<Integer>> memoryIndex) {
    }
}