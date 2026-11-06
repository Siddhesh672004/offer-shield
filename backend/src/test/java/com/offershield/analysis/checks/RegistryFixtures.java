package com.offershield.analysis.checks;

import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.MatchedMarker;
import com.offershield.registry.RegistryMatch;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Registry answers for the C10–C12 unit tests. Keys are made-up hex strings, never real HMACs of real numbers. */
final class RegistryFixtures {

    static final String PHONE_KEY = "1".repeat(64);
    static final String UPI_KEY = "2".repeat(64);

    private RegistryFixtures() {
    }

    static MatchedMarker marker(IndicatorType type, IndicatorStatus status, String key) {
        return new MatchedMarker(UUID.randomUUID(), type, status, key, null);
    }

    static MatchedMarker wording(IndicatorStatus status, int distance) {
        return new MatchedMarker(UUID.randomUUID(), IndicatorType.SIMHASH, status, "00000000000000ff", distance);
    }

    /** The registry was checked: the letter had exact keys and a fingerprint, and these markers matched. */
    static RegistryFindings found(int unverifiedReporters, MatchedMarker... markers) {
        return RegistryFindings.checked(true, true, new RegistryMatch(List.of(markers), unverifiedReporters), Set.of());
    }

    static Letter letter(String text, RegistryFindings findings) {
        return Letters.text(text).withRegistry(findings);
    }
}
