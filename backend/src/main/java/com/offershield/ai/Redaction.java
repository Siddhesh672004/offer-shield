package com.offershield.ai;

import java.util.Map;

/**
 * A masked text and its placeholder map (PRD §8.5, D-07): {@code [EMAIL_1]}, {@code [UPI_1]}, {@code [PHONE_1]},
 * {@code [NUMBER_1]}, {@code [NAME_1]} → the original value. The map never leaves the JVM: only {@link #text()} is
 * sent to a provider. {@link #toString()} never shows either.
 */
public record Redaction(String text, Map<String, String> originals) {

    public Redaction {
        originals = Map.copyOf(originals);
    }

    /** The original value of a placeholder, or {@code null} when this redaction never made it. */
    public String original(String placeholder) {
        return placeholder == null ? null : originals.get(placeholder.strip());
    }

    @Override
    public String toString() {
        return "Redaction[placeholders=" + originals.size() + ", redacted]";
    }
}
