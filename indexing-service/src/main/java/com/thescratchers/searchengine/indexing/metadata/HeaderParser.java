package com.thescratchers.searchengine.indexing.metadata;

import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts title, author, language and year from the header of a Project Gutenberg book.
 *
 * <p>Pure parsing: no file or database access. Field names are matched case-insensitively at the
 * start of a line. The year is the year of the Gutenberg "Release date", i.e. when the eBook was
 * published on Gutenberg, not when the original work was written.
 */
public final class HeaderParser {

    /** Title stored when the header has no {@code Title:} line (the column is NOT NULL). */
    public static final String UNTITLED = "Untitled";

    private static final int FLAGS = Pattern.MULTILINE | Pattern.CASE_INSENSITIVE;
    /** A field line followed by its indented, non-blank continuation lines (if any). */
    private static final String CONTINUED = "(.*(?:\\r?\\n[ \\t]+\\S.*)*)";
    private static final Pattern TITLE = Pattern.compile("^[ \\t]*Title:" + CONTINUED, FLAGS);
    private static final Pattern AUTHOR = Pattern.compile("^[ \\t]*Author:" + CONTINUED, FLAGS);
    private static final Pattern LANGUAGE = Pattern.compile("^[ \\t]*Language:(.*)$", FLAGS);
    private static final Pattern RELEASE_DATE = Pattern.compile("^[ \\t]*Release date:(.*)$", FLAGS);
    private static final Pattern LINE_BREAK = Pattern.compile("\\r?\\n");
    private static final Pattern YEAR = Pattern.compile("\\b(\\d{4})\\b");
    private static final Pattern LANGUAGE_SEPARATOR = Pattern.compile(",|;|\\band\\b");

    private HeaderParser() {
    }

    /**
     * Parses a Gutenberg header.
     *
     * <ul>
     *   <li>title: the {@code Title:} line, or {@value #UNTITLED} if missing;</li>
     *   <li>author: the {@code Author:} line, or {@code null} if missing;</li>
     *   <li>language: the first language of the {@code Language:} line converted with
     *       {@link LanguageCodes#toIso}, or {@code null} if missing or unknown;</li>
     *   <li>year: the first four-digit number of the {@code Release date:} line before any
     *       {@code [}, so the eBook number is never taken as the year, or {@code null}.</li>
     * </ul>
     *
     * <p>Indented lines right after {@code Title:} or {@code Author:} continue the value: title
     * lines are joined with a space and authors with {@code ", "} (e.g. "Jacob Grimm, Wilhelm Grimm").
     *
     * @param headerText header text; {@code null} is treated as an empty header
     */
    public static ParsedHeader parse(String headerText) {
        String text = headerText == null ? "" : headerText;
        String title = field(text, TITLE, " ");
        return new ParsedHeader(
                title == null ? UNTITLED : title,
                field(text, AUTHOR, ", "),
                language(field(text, LANGUAGE)),
                year(field(text, RELEASE_DATE)));
    }

    private static String language(String languageLine) {
        if (languageLine == null) {
            return null;
        }
        String first = LANGUAGE_SEPARATOR.split(languageLine, 2)[0];
        return LanguageCodes.toIso(first);
    }

    private static Integer year(String releaseDateLine) {
        if (releaseDateLine == null) {
            return null;
        }
        int bracket = releaseDateLine.indexOf('[');
        String date = bracket < 0 ? releaseDateLine : releaseDateLine.substring(0, bracket);
        Matcher matcher = YEAR.matcher(date);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** Trimmed value of the first line matching {@code pattern}, or {@code null} if absent or blank. */
    private static String field(String text, Pattern pattern) {
        return field(text, pattern, " ");
    }

    /**
     * Value of the first match of {@code pattern}, with each of its lines trimmed and the non-blank
     * ones joined with {@code separator}; {@code null} if absent or blank.
     */
    private static String field(String text, Pattern pattern, String separator) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        StringJoiner value = new StringJoiner(separator);
        for (String line : LINE_BREAK.split(matcher.group(1))) {
            if (!line.isBlank()) {
                value.add(line.trim());
            }
        }
        return value.length() == 0 ? null : value.toString();
    }
}
