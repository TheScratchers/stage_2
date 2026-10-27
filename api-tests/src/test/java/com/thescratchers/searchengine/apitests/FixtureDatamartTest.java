package com.thescratchers.searchengine.apitests;

import com.thescratchers.searchengine.common.Sqlite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureDatamartTest {

    @TempDir
    Path dataDir;

    @Test
    void createsTheDocumentedBooksAndPostingsAtTheContractPath() throws Exception {
        Path dbFile = FixtureDatamart.create(dataDir);

        assertEquals(dataDir.resolve("datamarts").resolve("datamart.db"), dbFile);
        assertEquals(6, count(dbFile, "books"));
        assertEquals(12, count(dbFile, "postings"));
        assertEquals(1, count(dbFile, "index_meta"));
    }

    @Test
    void recreatingReplacesThePreviousFixture() throws Exception {
        FixtureDatamart.create(dataDir);
        Path dbFile = FixtureDatamart.create(dataDir);

        assertEquals(6, count(dbFile, "books"));
    }

    private static int count(Path dbFile, String table) throws Exception {
        try (Connection conn = Sqlite.openReadOnly(dbFile);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
