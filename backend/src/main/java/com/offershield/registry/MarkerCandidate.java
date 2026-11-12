package com.offershield.registry;

import java.util.Objects;

/**
 * A marker a snapshot can add to the registry: its type and registry key (the registrable domain, an HMAC key in
 * lower-case hex, or the SimHash as 16 hex digits) and, for SIMHASH only, the fingerprint bits.
 */
public record MarkerCandidate(IndicatorType type, String key, Long simhash) {

    public MarkerCandidate {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        if ((type == IndicatorType.SIMHASH) != (simhash != null)) {
            throw new IllegalArgumentException("only a SIMHASH marker has fingerprint bits");
        }
    }

    static MarkerCandidate simhash(long fingerprint) {
        return new MarkerCandidate(IndicatorType.SIMHASH, simhashKey(fingerprint), fingerprint);
    }

    static String simhashKey(long fingerprint) {
        return String.format("%016x", fingerprint);
    }

    @Override
    public String toString() {
        return "MarkerCandidate[" + type + ", redacted]";
    }
}
