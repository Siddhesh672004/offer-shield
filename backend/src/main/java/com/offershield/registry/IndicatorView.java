package com.offershield.registry;

import java.time.Instant;
import java.util.UUID;

/**
 * A scam marker as other features see it (P10 admin queue, Revert): its type and status, the domain for a DOMAIN
 * marker ({@code null} otherwise: phone and UPI markers are only HMAC keys and are shown by type), how many distinct
 * people reported it, its version for {@code If-Match}, and when it was confirmed. P13 (N2): in a letter's evidence a
 * PHONE or UPI marker also carries its {@code source} ({@code LETTER}: written in the letter; {@code TYPED}: only typed
 * by the student) and a masked {@code hint} ("••••1234", "••••ar@okaxis"); both are {@code null} for DOMAIN and
 * SIMHASH markers and outside evidence.
 */
public record IndicatorView(UUID id, IndicatorType type, IndicatorStatus status, String domain, int reporterCount,
                            long version, Instant confirmedAt, String source, String hint) {

    /** A marker without a source or hint. */
    public IndicatorView(UUID id, IndicatorType type, IndicatorStatus status, String domain, int reporterCount,
                         long version, Instant confirmedAt) {
        this(id, type, status, domain, reporterCount, version, confirmedAt, null, null);
    }

    /** The same marker with a letter's source and masked hint (P13, N2). */
    IndicatorView withHint(String source, String hint) {
        return new IndicatorView(id, type, status, domain, reporterCount, version, confirmedAt, source, hint);
    }
}
