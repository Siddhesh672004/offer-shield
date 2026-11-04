package com.offershield.analysis.checks;

import com.offershield.registry.IndicatorType;
import com.offershield.registry.MatchedMarker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plain-language description of matched registry markers for C10/C12 evidence. A domain is shown as itself; phone
 * numbers and UPI IDs only by kind and count (the registry holds HMAC keys, never the values), wording as "the letter's
 * wording".
 */
final class RegistryMarkers {

    private RegistryMarkers() {
    }

    static String describe(List<MatchedMarker> markers) {
        List<String> parts = new ArrayList<>();
        markers.stream().filter(marker -> marker.type() == IndicatorType.DOMAIN).map(MatchedMarker::key).distinct()
                .forEach(domain -> parts.add("the sender domain " + domain));
        count(parts, markers, IndicatorType.PHONE, "a phone number", " phone numbers");
        count(parts, markers, IndicatorType.UPI, "a UPI ID", " UPI IDs");
        if (markers.stream().anyMatch(marker -> marker.type() == IndicatorType.SIMHASH)) {
            parts.add("the letter's wording");
        }
        return String.join(", ", parts);
    }

    /** True when every matched marker is a phone/UPI key that only the AI found (R-A label "AI-extracted"). */
    static boolean onlyThroughAi(List<MatchedMarker> markers, Set<String> aiOnlyKeys) {
        return !markers.isEmpty() && markers.stream().allMatch(marker -> (marker.type() == IndicatorType.PHONE
                || marker.type() == IndicatorType.UPI) && aiOnlyKeys.contains(marker.key()));
    }

    private static void count(List<String> parts, List<MatchedMarker> markers, IndicatorType type, String one,
                              String many) {
        long count = markers.stream().filter(marker -> marker.type() == type).map(MatchedMarker::key).distinct().count();
        if (count == 1) {
            parts.add(one);
        } else if (count > 1) {
            parts.add(count + many);
        }
    }
}
