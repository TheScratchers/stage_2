package com.thescratchers.searchengine.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTokenizerTest {

    @Test
    void referenceCase() {
        assertEquals(List.of("hello", "world", "testing"), TextTokenizer.tokenize("Hello World, 123! Testing."));
    }

    @Test
    void convertsToLowerCase() {
        assertEquals(List.of("upper", "lower", "mixed"), TextTokenizer.tokenize("UPPER lower MiXeD"));
    }

    @Test
    void discardsDigitsAndPunctuation() {
        assertEquals(List.of("abc", "hello", "world"), TextTokenizer.tokenize("123 abc 456 hello! world?"));
    }

    @Test
    void keepsAccentedWordsWhole() {
        assertEquals(List.of("café", "déjà", "vu", "naïve", "façade"),
                TextTokenizer.tokenize("Café, DÉJÀ-vu; Naïve FAÇADE"));
    }

    @Test
    void noStemmingAndNoStopwordRemoval() {
        assertEquals(List.of("the", "running", "cat", "is", "a", "mammal"),
                TextTokenizer.tokenize("the running cat is a mammal"));
    }

    @Test
    void emptyInputsReturnEmptyList() {
        assertTrue(TextTokenizer.tokenize(null).isEmpty());
        assertTrue(TextTokenizer.tokenize("").isEmpty());
        assertTrue(TextTokenizer.tokenize("   \t\n").isEmpty());
        assertTrue(TextTokenizer.tokenize("12345 !@#$%^&*()").isEmpty());
    }

    @Test
    void returnedListIsImmutable() {
        List<String> tokens = TextTokenizer.tokenize("alpha beta");
        assertThrows(UnsupportedOperationException.class, () -> tokens.add("gamma"));
    }

    @ParameterizedTest
    @CsvSource({
        "Apple123,  apple",
        "42Book,    book",
        "test-case, test",
        "don't,     don"
    })
    void firstTokenIsCorrect(String input, String expectedFirst) {
        List<String> tokens = TextTokenizer.tokenize(input);
        assertFalse(tokens.isEmpty());
        assertEquals(expectedFirst.trim(), tokens.get(0));
    }
}
