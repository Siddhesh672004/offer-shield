package com.offershield.registry;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The registry's answer for one letter (PRD §7.1 C10–C12): every live marker that matched (exact domain, phone or UPI
 * key; SimHash within {@value MatchService#NEAR_DUPLICATE_BITS} bits), and {@code unverifiedReporters} = how many
 * distinct people reported any of the matching UNVERIFIED markers (D-33.5); {@code exactUnverifiedReporters} counts
 * only those behind UNVERIFIED domain, phone or UPI markers (P13: used when wording-only reports must not count).
 * REJECTED markers never appear. Only CONFIRMED markers may force High (C10, C11); UNVERIFIED ones only feed C12 (R-B).
 */
public record RegistryMatch(List<MatchedMarker> markers, int unverifiedReporters, int exactUnverifiedReporters) {

    public static final RegistryMatch NONE = new RegistryMatch(List.of(), 0, 0);

    public RegistryMatch {
        markers = List.copyOf(markers);
        if (unverifiedReporters < 0 || exactUnverifiedReporters < 0) {
            throw new IllegalArgumentException("reporter counts must not be negative");
        }
    }

    /** A match whose UNVERIFIED reporters are all behind exact markers (or unknown: the same count for both). */
    public RegistryMatch(List<MatchedMarker> markers, int unverifiedReporters) {
        this(markers, unverifiedReporters, unverifiedReporters);
    }

    /** CONFIRMED exact matches: sender domain, phone number or UPI ID (C10). */
    public List<MatchedMarker> confirmedExact() {
        return markers.stream().filter(MatchedMarker::isConfirmed)
                .filter(marker -> marker.type() != IndicatorType.SIMHASH).toList();
    }

    /** The closest CONFIRMED wording match (C11), if any. */
    public Optional<MatchedMarker> closestConfirmedWording() {
        return markers.stream().filter(MatchedMarker::isConfirmed)
                .filter(marker -> marker.type() == IndicatorType.SIMHASH)
                .min(Comparator.comparing(MatchedMarker::distance));
    }

    /** UNVERIFIED matches of any type (C12). */
    public List<MatchedMarker> unverified() {
        return markers.stream().filter(marker -> marker.status() == IndicatorStatus.UNVERIFIED).toList();
    }

    /**
     * P13 (PRD §7.3 copied-letter guard for C12): the same answer without the UNVERIFIED wording markers, counting only
     * the people behind UNVERIFIED domain, phone or UPI markers. CONFIRMED markers stay (C11 has its own guard).
     */
    public RegistryMatch withoutUnverifiedWording() {
        List<MatchedMarker> kept = markers.stream().filter(marker -> marker.type() != IndicatorType.SIMHASH
                || marker.status() != IndicatorStatus.UNVERIFIED).toList();
        return new RegistryMatch(kept, exactUnverifiedReporters, exactUnverifiedReporters);
    }
}
