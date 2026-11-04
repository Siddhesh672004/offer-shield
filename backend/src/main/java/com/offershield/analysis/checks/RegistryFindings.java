package com.offershield.analysis.checks;

import com.offershield.registry.RegistryMatch;

import java.util.Objects;
import java.util.Set;

/**
 * The scam registry's answer for one letter, as C10–C12 read it (PRD §7.1, §10.1).
 * <ul>
 *   <li>{@code available}: false when the registry could not be asked (C10–C12 become UNAVAILABLE, report PARTIAL).</li>
 *   <li>{@code exactKeys}: the letter had a sender domain, phone number or UPI ID to compare (C10).</li>
 *   <li>{@code fingerprint}: the letter had a SimHash, i.e. at least 50 words (C11).</li>
 *   <li>{@code match}: the matching markers and the distinct unverified reporters.</li>
 *   <li>{@code aiOnlyKeys}: HMAC keys of phone numbers / UPI IDs that only the AI found in the letter (R-A: they can
 *       only add risk; a signal resting on them alone is labelled "AI-extracted").</li>
 * </ul>
 */
public record RegistryFindings(boolean available, boolean exactKeys, boolean fingerprint, RegistryMatch match,
                               Set<String> aiOnlyKeys) {

    /** Nothing was compared (no keys, or the registry was not asked). */
    public static final RegistryFindings NOTHING_TO_COMPARE = new RegistryFindings(true, false, false,
            RegistryMatch.NONE, Set.of());

    public RegistryFindings {
        Objects.requireNonNull(match, "match");
        aiOnlyKeys = aiOnlyKeys == null ? Set.of() : Set.copyOf(aiOnlyKeys);
    }

    public static RegistryFindings checked(boolean exactKeys, boolean fingerprint, RegistryMatch match,
                                           Set<String> aiOnlyKeys) {
        return new RegistryFindings(true, exactKeys, fingerprint, match, aiOnlyKeys);
    }

    public static RegistryFindings unavailable(boolean exactKeys, boolean fingerprint) {
        return new RegistryFindings(false, exactKeys, fingerprint, RegistryMatch.NONE, Set.of());
    }

    @Override
    public String toString() {
        return "RegistryFindings[available=" + available + ", markers=" + match.markers().size()
                + ", unverifiedReporters=" + match.unverifiedReporters() + "]";
    }
}
