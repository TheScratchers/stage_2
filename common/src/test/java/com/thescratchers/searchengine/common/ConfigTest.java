package com.thescratchers.searchengine.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTest {

    private static final String KEY = "STAGE2_CONFIG_TEST_KEY";

    @AfterEach
    void clearProperty() {
        System.clearProperty(KEY);
        System.clearProperty(Config.DATA_DIR);
    }

    @Test
    void returnsDefaultWhenUnset() {
        assertEquals("fallback", Config.get(KEY, "fallback"));
        assertEquals(7003, Config.getInt(KEY, 7003));
    }

    @Test
    void readsSystemPropertyWhenNoEnvironmentVariable() {
        System.setProperty(KEY, " 8080 ");
        assertEquals("8080", Config.get(KEY, "fallback"));
        assertEquals(8080, Config.getInt(KEY, 7003));
    }

    @Test
    void blankValueFallsBackToDefault() {
        System.setProperty(KEY, "   ");
        assertEquals("fallback", Config.get(KEY, "fallback"));
    }

    @Test
    void invalidIntegerFailsFast() {
        System.setProperty(KEY, "seven");
        assertThrows(IllegalArgumentException.class, () -> Config.getInt(KEY, 7003));
    }

    @Test
    void dataPathsDeriveFromDataDir() {
        System.setProperty(Config.DATA_DIR, "custom-data");
        Path root = Path.of("custom-data");
        assertEquals(root, Config.dataDir());
        assertEquals(root.resolve("datalake"), Config.datalakeDir());
        assertEquals(root.resolve("datamarts"), Config.datamartsDir());
        assertEquals(root.resolve("datamarts").resolve("datamart.db"), Config.datamartDb());
    }
}
