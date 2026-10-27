package com.thescratchers.searchengine.apitests;

import com.thescratchers.searchengine.common.Sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the fixture datamart used by {@code cases/search.json}, from the bundled
 * {@code fixture-datamart.sql}. Its six books are documented at the top of that file.
 */
public final class FixtureDatamart {

    private static final String RESOURCE = "/fixture-datamart.sql";

    private FixtureDatamart() {
    }

    /**
     * Creates {@code DATA_DIR/datamarts/datamart.db} under {@code dataDir}, replacing any previous
     * datamart there, and returns its path.
     */
    public static Path create(Path dataDir) throws IOException, SQLException {
        Path dbFile = dataDir.resolve("datamarts").resolve("datamart.db");
        for (String suffix : List.of("", "-wal", "-shm")) {
            Files.deleteIfExists(Path.of(dbFile + suffix));
        }
        try (Connection conn = Sqlite.open(dbFile); Statement stmt = conn.createStatement()) {
            for (String sql : statements()) {
                stmt.execute(sql);
            }
        }
        return dbFile;
    }

    /** Splits the SQL script into statements, skipping comment lines. */
    static List<String> statements() throws IOException {
        String script;
        try (InputStream in = FixtureDatamart.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + RESOURCE);
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        return statements;
    }
}
