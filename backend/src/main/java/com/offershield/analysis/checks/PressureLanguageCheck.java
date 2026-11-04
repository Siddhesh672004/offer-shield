package com.offershield.analysis.checks;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C9, pressure language (PRD §7.1): +10 for each different pressure phrase, at most two (+20, §7.2). Phrases: tight
 * deadlines ("within 24 hours", "today itself", "offer expires today"), scarcity ("limited seats", "first come, first
 * served"), "no interview required" and rush words ("respond immediately", "hurry"), plus Hinglish ("jaldi", "turant",
 * "aaj hi", "seats limited hain", "bina interview"). Source kind 1.
 */
@Component
public class PressureLanguageCheck implements RiskCheck {

    static final int POINTS_EACH = 10;
    static final int MAX_COUNTED = 2;
    private static final int MAX_SHOWN = 3;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final List<Pattern> PHRASES = List.of(
            Pattern.compile("\\bwithin\\s+(?:\\d{1,2}|one|two|six|twelve|twenty[- ]four|forty[- ]eight)\\s*"
                    + "(?:hours?|hrs?)\\b", FLAGS),
            Pattern.compile("\\btoday\\s+itself\\b|\\bby\\s+(?:today|tonight)\\b"
                    + "|\\bbefore\\s+(?:tonight|midnight|end\\s+of\\s+(?:the\\s+)?day)\\b"
                    + "|\\b(?:offer|link|seat)\\s+(?:expires|is\\s+valid\\s+only)\\s+(?:today|tonight)\\b", FLAGS),
            Pattern.compile("\\blimited\\s+(?:seats?|slots?|vacanc(?:y|ies)|positions?|openings?)\\b"
                    + "|\\b(?:only\\s+)?(?:a\\s+)?few\\s+(?:seats?|slots?)\\s+(?:left|remaining)\\b"
                    + "|\\bseats?\\s+(?:are\\s+)?filling\\s+(?:up\\s+)?fast\\b|\\bseats?\\s+limited\\b"
                    + "|\\bfirst[- ]come,?\\s+first[- ]served?\\b", FLAGS),
            Pattern.compile("\\bno\\s+interview(?:\\s+(?:is\\s+)?(?:required|needed))?\\b"
                    + "|\\bwithout\\s+(?:any\\s+)?interview\\b|\\bdirect\\s+(?:joining|selection|appointment)\\b"
                    + "|\\bbina\\s+interview\\b", FLAGS),
            Pattern.compile("\\b(?:pay|respond|reply|confirm|act|register|join)\\s+(?:now|immediately|urgently|asap)\\b"
                    + "|\\bhurry\\b|\\blast\\s+chance\\b|\\bdon['’]?t\\s+miss\\b", FLAGS),
            Pattern.compile("\\bjaldi\\b|\\bturant\\b|\\baaj\\s+hi\\b", FLAGS));

    @Override
    public CheckCode code() {
        return CheckCode.C9;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        // Every match by its position in the letter, then distinct phrases (ignoring case and spacing) in that order.
        Map<Integer, String> byPosition = new TreeMap<>();
        for (Pattern phrase : PHRASES) {
            Matcher matcher = phrase.matcher(letter.text());
            while (matcher.find()) {
                byPosition.putIfAbsent(matcher.start(), matcher.group().replaceAll("\\s+", " "));
            }
        }
        Map<String, String> found = new LinkedHashMap<>();
        byPosition.values().forEach(shown -> found.putIfAbsent(shown.toLowerCase(Locale.ROOT), shown));
        if (found.isEmpty()) {
            return CheckResult.passed(CheckCode.C9, "No pressure language was found in the letter.", 1);
        }
        int counted = Math.min(found.size(), MAX_COUNTED);
        String evidence = String.join(", ", found.values().stream().limit(MAX_SHOWN)
                .map(phrase -> "“" + phrase + "”").toList());
        return CheckResult.triggered(CheckCode.C9, counted * POINTS_EACH,
                "The letter pushes you to decide fast or skips normal hiring steps. Scammers use pressure so you "
                        + "don't stop to check.",
                Evidence.snippet(evidence), 1);
    }
}
