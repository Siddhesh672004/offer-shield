package com.offershield.analysis.checks;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a letter into clauses: at blank lines, bullets, sentence ends (. ! ? followed by a space or the end),
 * semicolons, commas, and the contrast words "but", "however" and "lekin". A single line break does not split,
 * because text extracted from a PDF breaks lines in mid-sentence ("we do not charge any⏎registration fee"). A dot or
 * comma inside a number, an address or "Rs. 500" does not split either, so an amount or an email address stays in one
 * clause. Used where a word's meaning depends on its neighbours, such as the D-09 "no fee" rule of C1.
 */
final class Clauses {

    private static final String SENTENCE_END = "[!?;\\u2022]+|\\R\\s*\\R|(?<![Rr][Ss])\\.(?=\\s|$)";
    private static final Pattern BOUNDARY = Pattern.compile(
            SENTENCE_END + "|,(?!\\d)|\\s(?:but|however|lekin)\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(SENTENCE_END);

    private Clauses() {
    }

    static List<String> of(String text) {
        return split(BOUNDARY, text);
    }

    /**
     * Sentences: the same splits as {@link #of(String)} except at commas and contrast words, for a rule whose context
     * may sit in another clause of the same sentence ("If you resign early, you pay the bond amount").
     */
    static List<String> sentences(String text) {
        return split(SENTENCE_BOUNDARY, text);
    }

    private static List<String> split(Pattern boundary, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(boundary.split(text))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
