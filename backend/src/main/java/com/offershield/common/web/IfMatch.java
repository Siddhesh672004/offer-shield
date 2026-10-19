package com.offershield.common.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optimistic-lock headers for decisions: responses carry {@code ETag: "<version>"} and the next decision sends it
 * back as {@code If-Match}. Both {@code 3} and the quoted ETag form {@code "3"} are accepted; weak tags, {@code *}
 * and lists are not, because a decision must name exactly the version the user looked at.
 */
public final class IfMatch {

    private static final Pattern VERSION = Pattern.compile("\"?(\\d{1,18})\"?");

    private IfMatch() {
    }

    /** @throws PreconditionRequiredException when the header is missing (428) */
    public static long requireVersion(String header) {
        if (header == null || header.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the version you are deciding on.");
        }
        String value = header.trim();
        Matcher matcher = VERSION.matcher(value);
        boolean balancedQuotes = value.startsWith("\"") == value.endsWith("\"");
        if (!matcher.matches() || !balancedQuotes) {
            throw new BadRequestException("INVALID_IF_MATCH", "If-Match must be the version from the ETag, e.g. \"3\".");
        }
        return Long.parseLong(matcher.group(1));
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }
}
