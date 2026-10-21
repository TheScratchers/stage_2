package com.thescratchers.searchengine.indexing.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanguageCodesTest {

    @Test
    void convertsKnownNamesIgnoringCase() {
        assertEquals("en", LanguageCodes.toIso("English"));
        assertEquals("es", LanguageCodes.toIso("SPANISH"));
        assertEquals("de", LanguageCodes.toIso("  german "));
    }

    @Test
    void keepsTwoLetterCodesInLowercase() {
        assertEquals("fr", LanguageCodes.toIso("fr"));
        assertEquals("fr", LanguageCodes.toIso("FR"));
    }

    @Test
    void unknownBlankOrNullGiveNull() {
        assertNull(LanguageCodes.toIso("Klingon"));
        assertNull(LanguageCodes.toIso("  "));
        assertNull(LanguageCodes.toIso(null));
    }
}
