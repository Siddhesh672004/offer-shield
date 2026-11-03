package com.offershield.analysis;

import com.offershield.ai.Redactor;
import com.offershield.common.Hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The letter's 64-bit wording fingerprint (PRD §10.1, D-18; computed here because the text lives in the analysis,
 * I-05), so a copy of a confirmed scam letter is found even when names, amounts and contact details were changed:
 * <ol>
 *   <li><b>Clean:</b> lower-case; email addresses, UPI IDs, phone numbers, long numbers and names (the D-07 masking of
 *       {@link Redactor}) become {@code [email]}, {@code [upi]}, {@code [phone]}, {@code [number]}, {@code [name]};
 *       every other number (amounts, dates, "15th") becomes {@code #} and month and day names {@code [month]} /
 *       {@code [day]}, so a changed name, date or amount is not a difference.</li>
 *   <li><b>Shingle:</b> overlapping 3-word phrases.</li>
 *   <li><b>Hash:</b> each phrase to 64 bits (the first 8 bytes of its SHA-256: stable across releases and JVMs).</li>
 *   <li><b>Vote:</b> for each bit position, each phrase votes +1 if its bit is 1, else −1.</li>
 *   <li><b>Fingerprint:</b> a bit is 1 when its total vote is positive.</li>
 * </ol>
 * Texts under {@value #MIN_WORDS} words get no fingerprint (too few phrases to be reliable). Two fingerprints are
 * near-duplicates when they differ in at most {@code MatchService.NEAR_DUPLICATE_BITS} (3) of 64 bits; MySQL's
 * {@code BIT_COUNT(a ^ b)} counts them.
 */
public final class SimHasher {

    public static final int MIN_WORDS = 50;
    private static final int SHINGLE = 3;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(email|upi|phone|number|name)_\\d+]");
    /** A number with optional separators inside and an ordinal ending: 5,000 · 6,00,000 · 1.5 · 15/11/2026 · 15th. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:/-]\\d+)*(?:st|nd|rd|th)?");
    private static final Pattern MONTH = Pattern.compile("(?<![\\p{L}])(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?"
            + "|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
            + "(?![\\p{L}])");
    private static final Pattern DAY = Pattern.compile(
            "(?<![\\p{L}])(?:mon|tues|wednes|thurs|fri|satur|sun)day(?![\\p{L}])");
    private static final Pattern TOKEN = Pattern.compile("\\[[a-z]+]|[\\p{L}\\p{M}\\p{N}#]+");

    private SimHasher() {
    }

    /** The fingerprint, or empty for a text under {@value #MIN_WORDS} words. */
    public static OptionalLong fingerprint(String text) {
        return fingerprintOfMasked(Redactor.mask(Redactor.clean(text)).text());
    }

    /** The same for a text already masked by {@link Redactor#mask}, so a caller masks it only once. */
    public static OptionalLong fingerprintOfMasked(String maskedText) {
        List<String> words = wordsOfMasked(maskedText);
        if (words.size() < MIN_WORDS) {
            return OptionalLong.empty();
        }
        MessageDigest sha256 = Hashing.sha256();
        int[] votes = new int[Long.SIZE];
        for (int i = 0; i + SHINGLE <= words.size(); i++) {
            String phrase = String.join(" ", words.subList(i, i + SHINGLE));
            long hash = firstLong(sha256.digest(phrase.getBytes(StandardCharsets.UTF_8)));
            for (int bit = 0; bit < Long.SIZE; bit++) {
                votes[bit] += ((hash >>> bit) & 1L) == 1L ? 1 : -1;
            }
        }
        long fingerprint = 0L;
        for (int bit = 0; bit < Long.SIZE; bit++) {
            if (votes[bit] > 0) {
                fingerprint |= 1L << bit;
            }
        }
        return OptionalLong.of(fingerprint);
    }

    /** Hamming distance: how many of the 64 bits differ. */
    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** Step 1: the cleaned words of a text. */
    static List<String> words(String text) {
        return wordsOfMasked(Redactor.mask(Redactor.clean(text)).text());
    }

    private static List<String> wordsOfMasked(String maskedText) {
        String cleaned = maskedText == null ? "" : maskedText.toLowerCase(Locale.ROOT);
        cleaned = PLACEHOLDER.matcher(cleaned).replaceAll(" [$1] ");
        cleaned = NUMBER.matcher(cleaned).replaceAll(" # ");
        cleaned = MONTH.matcher(cleaned).replaceAll(" [month] ");
        cleaned = DAY.matcher(cleaned).replaceAll(" [day] ");
        List<String> words = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(cleaned);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static long firstLong(byte[] digest) {
        long value = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        return value;
    }
}
