package com.thescratchers.searchengine.indexing.metadata;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts the language names used in Gutenberg headers ("English", "French", ...) to ISO 639-1
 * lowercase codes ("en", "fr", ...), which is how languages are stored in the datamart and
 * filtered by the search API.
 */
public final class LanguageCodes {

    private static final Pattern TWO_LETTER_CODE = Pattern.compile("[a-z]{2}");

    private static final Map<String, String> CODES = Map.ofEntries(
            Map.entry("english", "en"),
            Map.entry("spanish", "es"),
            Map.entry("french", "fr"),
            Map.entry("german", "de"),
            Map.entry("italian", "it"),
            Map.entry("portuguese", "pt"),
            Map.entry("dutch", "nl"),
            Map.entry("latin", "la"),
            Map.entry("finnish", "fi"),
            Map.entry("swedish", "sv"),
            Map.entry("danish", "da"),
            Map.entry("norwegian", "no"),
            Map.entry("russian", "ru"),
            Map.entry("greek", "el"),
            Map.entry("chinese", "zh"),
            Map.entry("japanese", "ja"),
            Map.entry("esperanto", "eo"));

    private LanguageCodes() {
    }

    /**
     * Returns the ISO 639-1 code of a language name, ignoring case and surrounding spaces. A value
     * that is already a two-letter code is returned in lowercase.
     *
     * @return the lowercase code, or {@code null} if {@code language} is null, blank or unknown
     */
    public static String toIso(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if (TWO_LETTER_CODE.matcher(normalized).matches()) {
            return normalized;
        }
        return CODES.get(normalized);
    }
}
