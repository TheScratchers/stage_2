package com.thescratchers.searchengine.datamarts.index;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqliteIndexStorage implements InvertedIndexStorage {
    private final String dbUrl = "jdbc:sqlite:data/datamarts/inverted_index.db";
    private Connection connection;

    public SqliteIndexStorage() {
        try {
            connection = DriverManager.getConnection(dbUrl);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA synchronous = OFF");
                stmt.execute("PRAGMA journal_mode = MEMORY");
                stmt.execute("CREATE TABLE IF NOT EXISTS inverted_index (term TEXT, book_id INTEGER, PRIMARY KEY(term, book_id))");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error initializing SQLite connection", e);
        }
    }

    @Override
    public void save(String term, int bookId) {
        String sql = "INSERT OR IGNORE INTO inverted_index (term, book_id) VALUES (?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, term);
            pstmt.setInt(2, bookId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error saving term incrementally", e);
        }
    }

    @Override
    public void build(Map<String, List<Integer>> memoryIndex) {
        String sql = "INSERT OR IGNORE INTO inverted_index (term, book_id) VALUES (?, ?)";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                int count = 0;
                for (Map.Entry<String, List<Integer>> entry : memoryIndex.entrySet()) {
                    String term = entry.getKey();
                    for (int bookId : entry.getValue()) {
                        pstmt.setString(1, term);
                        pstmt.setInt(2, bookId);
                        pstmt.addBatch();
                        
                        if (++count % 10000 == 0) {
                            pstmt.executeBatch();
                        }
                    }
                }
                pstmt.executeBatch();
                connection.commit();
            }
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new RuntimeException("Error building SQLite index", e);
        }
    }

    @Override
    public List<Integer> search(String term) {
        List<Integer> results = new ArrayList<>();
        String sql = "SELECT book_id FROM inverted_index WHERE term = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, term);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getInt("book_id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error querying SQLite", e);
        }
        return results;
    }
}