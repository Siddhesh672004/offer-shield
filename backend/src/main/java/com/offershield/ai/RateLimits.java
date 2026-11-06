package com.offershield.ai;

import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How long a model rests after a 429 (PRD §8.4, V-06, I-27): {@code retry-after} in seconds when present; else the
 * {@code x-ratelimit-reset-requests} / {@code x-ratelimit-reset-tokens} headers, which Groq sends as Go-style
 * durations ("2m59.56s", "7.66s"), taking the reset of the limit that is used up (remaining = 0), or the longest
 * reset when neither says so; else the fallback (60 s). Always between 1 s and 24 h.
 */
final class RateLimits {

    private static final Duration MIN = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofHours(24);
    private static final int MAX_HEADER_LENGTH = 40;
    private static final Pattern PLAIN_SECONDS = Pattern.compile("\\d{1,9}(?:\\.\\d{1,9})?");
    private static final Pattern PART = Pattern.compile("(\\d{1,9}(?:\\.\\d{1,9})?)(ms|us|µs|ns|h|m|s)");
    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

    private RateLimits() {
    }

    static Duration restFor(HttpHeaders headers, Duration fallback) {
        Duration retryAfter = parseDuration(headers.getFirst("retry-after"));
        if (retryAfter != null) {
            return bounded(retryAfter);
        }
        Duration requests = parseDuration(headers.getFirst("x-ratelimit-reset-requests"));
        Duration tokens = parseDuration(headers.getFirst("x-ratelimit-reset-tokens"));
        List<Duration> resets = new ArrayList<>();
        if (requests != null && "0".equals(strip(headers.getFirst("x-ratelimit-remaining-requests")))) {
            resets.add(requests);
        }
        if (tokens != null && "0".equals(strip(headers.getFirst("x-ratelimit-remaining-tokens")))) {
            resets.add(tokens);
        }
        if (resets.isEmpty()) {
            if (requests != null) {
                resets.add(requests);
            }
            if (tokens != null) {
                resets.add(tokens);
            }
        }
        return bounded(resets.isEmpty() ? fallback : Collections.max(resets));
    }

    /** "2m59.56s", "7.66s", "1h2m3s", "250ms", or plain seconds ("12"); {@code null} when unreadable or negative. */
    static Duration parseDuration(String value) {
        String text = strip(value);
        if (text == null || text.isEmpty() || text.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        if (PLAIN_SECONDS.matcher(text).matches()) {
            return toDuration(new BigDecimal(text).multiply(NANOS_PER_SECOND));
        }
        BigDecimal nanos = BigDecimal.ZERO;
        Matcher part = PART.matcher(text);
        int position = 0;
        while (position < text.length()) {
            if (!part.find(position) || part.start() != position) {
                return null;
            }
            nanos = nanos.add(new BigDecimal(part.group(1)).multiply(nanosPer(part.group(2))));
            position = part.end();
        }
        return toDuration(nanos);
    }

    private static BigDecimal nanosPer(String unit) {
        return switch (unit) {
            case "h" -> BigDecimal.valueOf(3_600_000_000_000L);
            case "m" -> BigDecimal.valueOf(60_000_000_000L);
            case "s" -> NANOS_PER_SECOND;
            case "ms" -> BigDecimal.valueOf(1_000_000L);
            case "us", "µs" -> BigDecimal.valueOf(1_000L);
            default -> BigDecimal.ONE;
        };
    }

    private static Duration toDuration(BigDecimal nanos) {
        BigDecimal max = BigDecimal.valueOf(MAX.toNanos());
        return Duration.ofNanos(nanos.min(max).longValue());
    }

    private static Duration bounded(Duration duration) {
        if (duration.compareTo(MIN) < 0) {
            return MIN;
        }
        return duration.compareTo(MAX) > 0 ? MAX : duration;
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
