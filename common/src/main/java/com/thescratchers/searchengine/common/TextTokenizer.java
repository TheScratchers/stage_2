package com.thescratchers.searchengine.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single tokenizer used both to build the inverted index and to tokenize search queries, so
 * that indexed terms and query terms always match.
 *
 * <p>Rule: lowercase the text with {@link Locale#ROOT}, then extract maximal runs of Unicode
 * letters ({@code \p{L}+}). Digits and punctuation are dropped; there is no stemming and no
 * stopword removal. Unlike Stage 1 ({@code [A-Za-z]+}), accented words such as "café" or
 * "naïve" are kept whole instead of being split, which matters now that searches can be filtered
 * by language.
 */
public final class TextTokenizer {

    private static final Pattern WORD_PATTERN = Pattern.compile("\\p{L}+");

    private TextTokenizer() {
    }

    /** Returns the tokens of {@code text} in order of appearance; empty if it is null or blank. */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return Collections.unmodifiableList(tokens);
    }
}
