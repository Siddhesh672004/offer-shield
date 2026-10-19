package com.offershield.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/** Admin allow-list from {@code ADMIN_EMAILS} (comma separated). Matching ignores case and surrounding whitespace. */
@ConfigurationProperties("offershield.admin")
public record AdminProperties(List<String> emails) {

    public boolean isAdmin(String email) {
        if (emails == null || email == null) {
            return false;
        }
        String candidate = normalise(email);
        if (candidate.isEmpty()) {
            return false;
        }
        return emails.stream().map(AdminProperties::normalise).anyMatch(candidate::equals);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
