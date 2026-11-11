package com.offershield.registry;

import java.time.Instant;
import java.util.UUID;

/** One {@code indicator} row as the repository reads it. */
record IndicatorRow(UUID id, IndicatorType type, String key, IndicatorStatus status, int reporterCount, long version,
                    UUID confirmedBy, Instant confirmedAt) {

    IndicatorView view() {
        return new IndicatorView(id, type, status, type == IndicatorType.DOMAIN ? key : null, reporterCount, version,
                confirmedAt);
    }

    @Override
    public String toString() {
        return "IndicatorRow[" + id + ", " + type + ", " + status + "]";
    }
}
