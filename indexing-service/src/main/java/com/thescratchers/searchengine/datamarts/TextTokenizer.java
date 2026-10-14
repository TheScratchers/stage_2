package com.thescratchers.searchengine.datamarts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextTokenizer {

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");

    private TextTokenizer() {}

    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        String lower = text.toLowerCase();
        Matcher matcher = WORD_PATTERN.matcher(lower);

        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return Collections.unmodifiableList(tokens);
    }

    public static void main(String[] args) {
        String input      = "Hello World, 123! Testing.";
        List<String> expected = List.of("hello", "world", "testing");
        List<String> result   = tokenize(input);

        System.out.println("Input   : \"" + input + "\"");
        System.out.println("Result  : " + result);
        System.out.println("Expected: " + expected);

        if (result.equals(expected)) {
            System.out.println("[OK] Tokenizer contract verified.");
        } else {
            System.err.println("[FAIL] Tokenizer output does not match expected!");
            System.exit(1);
        }
    }
}