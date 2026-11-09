package com.offershield.company;

import java.util.regex.Pattern;

/** The shape of a known company's name: letters (any script), digits, spaces and {@code & . , ' ( ) -}. */
final class KnownCompanyNames {

    static final String ALLOWED = "[\\p{L}\\p{N}][\\p{L}\\p{N} &.,'()-]*";
    static final String ALLOWED_MESSAGE =
            "must start with a letter or digit and use only letters, digits, spaces and & . , ' ( ) -";
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private KnownCompanyNames() {
    }

    /** Trimmed, with every run of whitespace turned into one space; {@code null} stays {@code null}. */
    static String clean(String name) {
        return name == null ? null : SPACES.matcher(name.strip()).replaceAll(" ");
    }
}
