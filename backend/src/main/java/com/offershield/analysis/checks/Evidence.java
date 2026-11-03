package com.offershield.analysis.checks;

import java.util.regex.Pattern;

/**
 * Turns a piece of the letter into report evidence: whitespace collapsed, email addresses, UPI IDs and phone numbers
 * masked (evidence travels in {@code analysis.completed} and later the registry snapshot, D-27), and cut to
 * {@value #MAX_LENGTH} characters (02 §5).
 */
public final class Evidence {

    public static final int MAX_LENGTH = 200;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){1,8}");
    private static final Pattern UPI = Pattern.compile("[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,34}\\b");
    /** Indian mobile numbers (optionally +91 / 0, optionally split 5-5) and any other number written with a +. */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\d+])(?:(?:\\+|00)?91[\\s-]?|0)?[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)|\\+\\d[\\d\\s-]{7,16}\\d");

    private Evidence() {
    }

    public static String snippet(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = WHITESPACE.matcher(text).replaceAll(" ").strip();
        cleaned = EMAIL.matcher(cleaned).replaceAll("[email]");
        cleaned = UPI.matcher(cleaned).replaceAll("[UPI ID]");
        cleaned = PHONE.matcher(cleaned).replaceAll("[phone]");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_LENGTH ? cleaned : cleaned.substring(0, MAX_LENGTH - 1) + "…";
    }
}
