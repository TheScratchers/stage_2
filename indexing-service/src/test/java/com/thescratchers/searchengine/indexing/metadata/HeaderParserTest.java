package com.thescratchers.searchengine.indexing.metadata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HeaderParserTest {

    private static final Path SAMPLES = Path.of("..", "samples", "books");

    @Test
    void parsesPrideAndPrejudiceHeader() throws IOException {
        String header = Files.readString(SAMPLES.resolve("1342.header.txt"), StandardCharsets.UTF_8);

        ParsedHeader parsed = HeaderParser.parse(header);

        assertEquals("Pride and Prejudice", parsed.title());
        assertEquals("Jane Austen", parsed.author());
        assertEquals("en", parsed.language());
        assertEquals(1998, parsed.year());
    }

    @Test
    void missingFieldsBecomeNullAndTitleBecomesUntitled() {
        ParsedHeader parsed = HeaderParser.parse("The Project Gutenberg eBook\r\n\r\nCredits: someone\r\n");

        assertEquals(HeaderParser.UNTITLED, parsed.title());
        assertNull(parsed.author());
        assertNull(parsed.language());
        assertNull(parsed.year());
    }

    @Test
    void nullHeaderIsTreatedAsEmpty() {
        assertEquals(new ParsedHeader(HeaderParser.UNTITLED, null, null, null), HeaderParser.parse(null));
    }

    @Test
    void takesTheFirstOfSeveralLanguages() {
        assertEquals("en", HeaderParser.parse("Title: X\nLanguage: English, French\n").language());
    }

    @Test
    void fieldNamesAreCaseInsensitive() {
        ParsedHeader parsed = HeaderParser.parse("TITLE: Dracula\nauthor: Bram Stoker\nRelease Date: October 1995\n");

        assertEquals("Dracula", parsed.title());
        assertEquals("Bram Stoker", parsed.author());
        assertEquals(1995, parsed.year());
    }

    @Test
    void unknownLanguageBecomesNull() {
        assertNull(HeaderParser.parse("Language: Klingon\n").language());
    }
}
