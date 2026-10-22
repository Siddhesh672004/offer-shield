package com.offershield.common;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CLAUDE.md product rule: the product never calls an offer "verified", "safe", "legit" or "guaranteed" (PRD §19.1).
 * Text that did not come from our own fixed copy (AI output, AI-extracted facts) is checked with
 * {@link #violates(String)} before it is shown; the check is deliberately broad ("unsafe", "legitimate" and
 * "guarantees" also fail), so in doubt the text is not shown. Only the mandated trust label "(not verified)" /
 * "unverified" is allowed.
 */
public final class CopyRules {

    private static final List<String> FORBIDDEN = List.of("verified", "safe", "legit", "guarantee");
    /** The mandated trust label "Reported as a scam by N users (not verified)" is allowed. */
    private static final Pattern NOT_VERIFIED = Pattern.compile("\\bnot\\s+verified\\b|\\bunverified\\b");

    private CopyRules() {
    }

    public static boolean violates(String text) {
        if (text == null) {
            return false;
        }
        String lower = NOT_VERIFIED.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return FORBIDDEN.stream().anyMatch(lower::contains);
    }
}
