package com.thescratchers.searchengine.common;

import java.nio.file.Path;

/**
 * Central access to the runtime configuration shared by every service.
 *
 * <p>A value is resolved from the environment variable with the given name, then from the JVM
 * system property with the same name ({@code -DKEY=value}, handy for tests and local runs), and
 * finally from the supplied default. No service hard-codes ports or data paths: they all come
 * from here, so the whole deployment can be reconfigured without touching the code.
 */
public final class Config {

    /** Environment variable holding the root of the datalake and datamarts. */
    public static final String DATA_DIR = "DATA_DIR";

    private static final String DEFAULT_DATA_DIR = "./data";

    private Config() {
    }

    /** Returns the configured value for {@code key}, or {@code defaultValue} when it is unset or blank. */
    public static String get(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    /**
     * Returns the configured integer for {@code key}, or {@code defaultValue} when it is unset.
     *
     * @throws IllegalArgumentException if the value is set but is not an integer, so a typo in the
     *                                  deployment fails at startup instead of being silently ignored
     */
    public static int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer but was '" + value + "'", e);
        }
    }

    /** Root data directory ({@code DATA_DIR}, default {@code ./data}). */
    public static Path dataDir() {
        return Path.of(get(DATA_DIR, DEFAULT_DATA_DIR));
    }

    /** Datalake root: {@code dataDir()/datalake}. */
    public static Path datalakeDir() {
        return dataDir().resolve("datalake");
    }

    /** Datamarts root: {@code dataDir()/datamarts}. */
    public static Path datamartsDir() {
        return dataDir().resolve("datamarts");
    }

    /** SQLite datamart with books, postings and index metadata: {@code datamartsDir()/datamart.db}. */
    public static Path datamartDb() {
        return datamartsDir().resolve("datamart.db");
    }
}
