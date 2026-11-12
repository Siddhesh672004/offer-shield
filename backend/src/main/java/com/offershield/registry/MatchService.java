package com.offershield.registry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registry matching for one letter (PRD §7.1 C10–C12, §10.1; R-F: an in-process call from the analysis, in its own
 * short read-only transaction, never inside the report's transaction): exact matches on the sender domain and on the
 * phone and UPI HMAC keys, and SimHash matches within {@value #NEAR_DUPLICATE_BITS} differing bits
 * ({@code BIT_COUNT(a ^ b)}). REJECTED markers are ignored. D-08: a free-mail or known company's official domain is
 * never looked up, so it can never match even if it was stored before the company was listed. The unverified count is
 * the number of distinct people across every matching UNVERIFIED marker (D-33.5).
 */
@Service
public class MatchService {

    /** PRD §7.1 C11: at most 3 of the 64 SimHash bits differ. */
    public static final int NEAR_DUPLICATE_BITS = 3;

    private final IndicatorRepository indicators;
    private final SharedDomains sharedDomains;

    public MatchService(IndicatorRepository indicators, SharedDomains sharedDomains) {
        this.indicators = indicators;
        this.sharedDomains = sharedDomains;
    }

    @Transactional(readOnly = true)
    public RegistryMatch match(MarkerQuery query) {
        List<MatchedMarker> found = new ArrayList<>();
        if (query.domain() != null && !sharedDomains.isShared(query.domain())) {
            found.addAll(indicators.findLive(IndicatorType.DOMAIN, List.of(query.domain())));
        }
        found.addAll(indicators.findLive(IndicatorType.PHONE, query.phoneHmacs()));
        found.addAll(indicators.findLive(IndicatorType.UPI, query.upiHmacs()));
        if (query.simhash() != null) {
            found.addAll(indicators.findLiveNear(query.simhash(), NEAR_DUPLICATE_BITS));
        }
        List<MatchedMarker> unverified = found.stream()
                .filter(marker -> marker.status() == IndicatorStatus.UNVERIFIED).toList();
        int reporters = indicators.countDistinctReporters(unverified.stream().map(MatchedMarker::id).toList());
        List<UUID> exact = unverified.stream().filter(marker -> marker.type() != IndicatorType.SIMHASH)
                .map(MatchedMarker::id).toList();
        // P13: the people behind domain, phone and UPI reports only (C12's copied-letter guard drops wording reports).
        int exactReporters = exact.size() == unverified.size() ? reporters : indicators.countDistinctReporters(exact);
        return new RegistryMatch(found, reporters, exactReporters);
    }
}
