package com.offershield.registry;

import java.util.UUID;

/**
 * A registry marker that matched a letter: its type, status and key (the domain itself for DOMAIN; an HMAC key or the
 * SimHash hex otherwise, never a raw value) and, for SIMHASH, how many of the 64 bits differ.
 */
public record MatchedMarker(UUID id, IndicatorType type, IndicatorStatus status, String key, Integer distance) {

    public boolean isConfirmed() {
        return status == IndicatorStatus.CONFIRMED;
    }

    @Override
    public String toString() {
        return "MatchedMarker[" + id + ", " + type + ", " + status + "]";
    }
}
