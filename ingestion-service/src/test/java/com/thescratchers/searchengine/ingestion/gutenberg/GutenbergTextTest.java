package com.thescratchers.searchengine.ingestion.gutenberg;

import com.thescratchers.searchengine.ingestion.SampleBooks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GutenbergTextTest {

    @Test
    void reproducesTheStage1SampleFilesExactly() throws Exception {
        SplitBook book = GutenbergText.split(1342, SampleBooks.rawText(1342));

        assertEquals(SampleBooks.header(1342), book.header());
        assertEquals(SampleBooks.body(1342), book.body());
    }

    @Test
    void acceptsThisInsteadOfTheAndAnyCase() throws Exception {
        String raw = "Title: Old Book\n\n"
                + "  ***  start of this project gutenberg ebook old book ***\n"
                + "Once upon a time.\n"
                + "*** End Of This Project Gutenberg E-Book old book ***\n"
                + "license";

        SplitBook book = GutenbergText.split(7, raw);

        assertEquals("Title: Old Book", book.header());
        assertEquals("Once upon a time.", book.body());
    }

    @Test
    void endMarkerBeforeStartIsIgnored() throws Exception {
        String raw = "*** END OF THE PROJECT GUTENBERG EBOOK X ***\n"
                + "Title: X\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\nbody\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK X ***\n";

        assertEquals("body", GutenbergText.split(1, raw).body());
    }

    @Test
    void missingStartMarkerIsInvalidFormat() {
        DownloadException e = assertThrows(DownloadException.class,
                () -> GutenbergText.split(5, "Title: X\nno markers at all\n"));
        assertEquals(DownloadException.Reason.INVALID_FORMAT, e.reason());
        assertEquals(5, e.bookId());
    }

    @Test
    void missingEndMarkerIsInvalidFormat() {
        DownloadException e = assertThrows(DownloadException.class,
                () -> GutenbergText.split(5, "Title: X\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\nbody"));
        assertEquals(DownloadException.Reason.INVALID_FORMAT, e.reason());
    }
}
