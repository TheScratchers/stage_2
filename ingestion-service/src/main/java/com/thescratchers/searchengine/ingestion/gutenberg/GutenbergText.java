package com.thescratchers.searchengine.ingestion.gutenberg;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a raw Project Gutenberg text into header and body.
 *
 * <p>The header is everything before the {@code *** START OF ... PROJECT GUTENBERG EBOOK ...}
 * line and the body is everything between that line and the matching {@code *** END OF ...} line;
 * the marker lines themselves and the license after the END marker are discarded. Both markers
 * are matched case-insensitively and accept "THE" and "THIS" (older texts use the latter), so
 * valid books are no longer rejected as they were in Stage 1.
 */
public final class GutenbergText {

    private static final Pattern START_MARKER = Pattern.compile(
            "^[ \\t]*\\*{3}[ \\t]*START OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK.*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern END_MARKER = Pattern.compile(
            "^[ \\t]*\\*{3}[ \\t]*END OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK.*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private GutenbergText() {
    }

    /**
     * Splits {@code rawText} at its markers; header and body are returned without leading or
     * trailing blank space.
     *
     * @throws DownloadException with reason {@code INVALID_FORMAT} if a marker is missing
     */
    public static SplitBook split(int bookId, String rawText) throws DownloadException {
        Matcher start = START_MARKER.matcher(rawText);
        if (!start.find()) {
            throw new DownloadException(bookId, DownloadException.Reason.INVALID_FORMAT,
                    "START marker of the Project Gutenberg text not found");
        }
        Matcher end = END_MARKER.matcher(rawText);
        if (!end.find(start.end())) {
            throw new DownloadException(bookId, DownloadException.Reason.INVALID_FORMAT,
                    "END marker of the Project Gutenberg text not found");
        }
        return new SplitBook(
                rawText.substring(0, start.start()).strip(),
                rawText.substring(start.end(), end.start()).strip());
    }
}
