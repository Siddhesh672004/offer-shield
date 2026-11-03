package com.offershield.analysis.checks;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Domains of the email addresses written in a letter. Flat, bounded regex classes only (safe on hostile text). */
public final class EmailDomains {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9.-]{1,253})");

    private EmailDomains() {
    }

    /** The distinct host names of the email addresses in {@code text}, lower-cased, in the order found. */
    public static Set<String> in(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null) {
            return found;
        }
        Matcher matcher = EMAIL.matcher(text);
        while (matcher.find()) {
            String domain = domainName(matcher.group(1));
            if (domain != null) {
                found.add(domain);
            }
        }
        return found;
    }

    /** A host name with at least two labels and a letters-only top-level label, lower-cased; else {@code null}. */
    static String domainName(String candidate) {
        if (candidate == null) {
            return null;
        }
        String domain = candidate.toLowerCase(Locale.ROOT);
        int end = domain.length();
        while (end > 0 && (domain.charAt(end - 1) == '.' || domain.charAt(end - 1) == '-')) {
            end--; // sentence punctuation after an address: "write to hr@acme.example."
        }
        domain = domain.substring(0, end);
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return null;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return null;
            }
        }
        String topLevel = labels[labels.length - 1];
        return topLevel.length() >= 2 && topLevel.chars().allMatch(ch -> ch >= 'a' && ch <= 'z') ? domain : null;
    }
}
