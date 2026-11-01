package com.offershield.analysis;

import com.offershield.ai.Redaction;
import com.offershield.ai.Redactor;
import com.offershield.analysis.checks.CopiedLetterGuard;
import com.offershield.analysis.checks.Letter;
import com.offershield.analysis.checks.RegistryFindings;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.registry.IndicatorHasher;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.MatchService;
import com.offershield.registry.RegistryMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The analysis side of the registry (PRD §7.1 C10–C12, §10.1; R-F): builds the letter's registry keys and asks the
 * registry's {@link MatchService} in-process, outside any of the analysis' transactions (the match runs in its own
 * short read-only one). Phone numbers and UPI IDs come from the letter text (the same patterns as the D-07 masking,
 * {@link Redactor}), the typed fields and the AI's grounded candidates (R-A: those only add keys); each is hashed at
 * once with the registry's {@link IndicatorHasher}, so no raw value leaves this method. At most {@value #MAX_KEYS}
 * of each kind are kept ({@code complete = false} beyond). A failure never stops the report: the registry checks
 * become UNAVAILABLE (report PARTIAL). Logs carry the exception class only (D-27).
 *
 * <p>P13: each phone / UPI key also gets its source (in the letter, or only typed by the student) and a masked hint
 * for reviewers (N2); a failure while computing the keys keeps the SSRF-validated sender domain (silent failure 6);
 * and unverified wording-only reports are discounted for the official sender of the company the letter names (the
 * PRD §7.3 copied-letter guard applied to C12).
 */
@Component
public class RegistryLookup {

    static final int MAX_KEYS = 10;
    private static final String TYPE_PHONE = "PHONE";
    private static final String TYPE_UPI = "UPI";
    private static final String SOURCE_LETTER = "LETTER";
    private static final String SOURCE_TYPED = "TYPED";
    private static final Logger log = LoggerFactory.getLogger(RegistryLookup.class);

    private final IndicatorHasher hasher;
    private final MatchService matches;
    private final CopiedLetterGuard guard;

    public RegistryLookup(IndicatorHasher hasher, MatchService matches, CopiedLetterGuard guard) {
        this.hasher = hasher;
        this.matches = matches;
        this.guard = guard;
    }

    /** The letter's keys and the registry's answer for them. */
    public Result lookup(Letter letter) {
        LetterMarkers markers;
        try {
            markers = markers(letter);
        } catch (RuntimeException ex) {
            // P13 (silent failure 6): keep the sender domain the SSRF guard already passed, so a later report or
            // confirmation can still record it; the keys are marked incomplete and C10–C12 are UNAVAILABLE.
            log.warn("registry keys failed error={}", ex.getClass().getSimpleName());
            LetterMarkers domainOnly = LetterMarkers.domainOnly(
                    letter.domain() == null ? null : letter.domain().registrableDomain());
            return new Result(domainOnly, RegistryFindings.unavailable(domainOnly.hasExactKeys(), false));
        }
        boolean fingerprint = markers.simhash() != null;
        if (!markers.hasExactKeys() && !fingerprint) {
            return new Result(markers, RegistryFindings.NOTHING_TO_COMPARE);
        }
        try {
            RegistryMatch match = discountWordingReports(letter, matches.match(markers.query()));
            return new Result(markers, RegistryFindings.checked(markers.hasExactKeys(), fingerprint, match,
                    markers.aiOnlyKeys()));
        } catch (RuntimeException ex) {
            log.warn("registry match failed error={}", ex.getClass().getSimpleName());
            return new Result(markers, RegistryFindings.unavailable(markers.hasExactKeys(), fingerprint));
        }
    }

    /**
     * P13 (PRD §7.3 copied-letter guard, applied to C12 too): a genuine employer's letter can match unverified reports
     * of a copy of itself, so when the sender is on an official domain of the company the letter names (by rules,
     * never the AI), unverified wording-only reports don't count; domain, phone and UPI reports still do. If the guard
     * cannot run, every report counts (the safe side for the student).
     */
    private RegistryMatch discountWordingReports(Letter letter, RegistryMatch match) {
        boolean wordingReported = match.unverified().stream()
                .anyMatch(marker -> marker.type() == IndicatorType.SIMHASH);
        if (!wordingReported) {
            return match;
        }
        try {
            return guard.officialSender(letter).isPresent() ? match.withoutUnverifiedWording() : match;
        } catch (RuntimeException ex) {
            log.warn("copied-letter guard failed error={}", ex.getClass().getSimpleName());
            return match;
        }
    }

    LetterMarkers markers(Letter letter) {
        Redaction redaction = Redactor.mask(Redactor.clean(letter.text()));
        Keys phones = new Keys(TYPE_PHONE);
        Keys upiIds = new Keys(TYPE_UPI);
        // In placeholder order ([PHONE_1], [PHONE_2], …), i.e. the order the values appear in the letter.
        List<Map.Entry<String, String>> found = redaction.originals().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(String::length)
                        .thenComparing(Comparator.naturalOrder())))
                .toList();
        for (Map.Entry<String, String> entry : found) {
            if (entry.getKey().startsWith("[PHONE_")) {
                phones.inLetter(hasher.phone(entry.getValue()), hasher.phoneHint(entry.getValue()), false);
            } else if (entry.getKey().startsWith("[UPI_")) {
                upiIds.inLetter(hasher.upi(entry.getValue()), hasher.upiHint(entry.getValue()), false);
            }
        }
        phones.typed(hasher.phone(letter.recruiterPhone()), hasher.phoneHint(letter.recruiterPhone()));
        upiIds.typed(hasher.upi(letter.upiId()), hasher.upiHint(letter.upiId()));
        letter.ai().phoneNumbers().forEach(value ->
                phones.inLetter(hasher.phone(value), hasher.phoneHint(value), true));
        letter.ai().upiIds().forEach(value -> upiIds.inLetter(hasher.upi(value), hasher.upiHint(value), true));
        OptionalLong simhash = SimHasher.fingerprintOfMasked(redaction.text());
        Set<String> aiOnly = new HashSet<>(phones.aiOnly());
        aiOnly.addAll(upiIds.aiOnly());
        List<AnalysisCompletedEvent.MarkerHint> hints = new ArrayList<>(phones.hints());
        hints.addAll(upiIds.hints());
        return new LetterMarkers(letter.domain() == null ? null : letter.domain().registrableDomain(), phones.kept(),
                upiIds.kept(), simhash.isPresent() ? simhash.getAsLong() : null, aiOnly,
                phones.complete() && upiIds.complete(), hints);
    }

    /** The registry keys of one letter and the registry's answer (C10–C12 read the findings). */
    public record Result(LetterMarkers markers, RegistryFindings findings) {
    }

    /**
     * Distinct keys in the order found: the letter's and the typed ones first, then the AI's. Remembers which only the
     * AI found (R-A label), which are in the letter (its text or the AI's grounded candidates) rather than only typed,
     * and each key's masked hint (P13, N2).
     */
    private static final class Keys {

        private final String type;
        private final Set<String> fromRules = new LinkedHashSet<>();
        private final Set<String> inLetter = new HashSet<>();
        private final Set<String> all = new LinkedHashSet<>();
        private final Map<String, String> hints = new HashMap<>();

        Keys(String type) {
            this.type = type;
        }

        void inLetter(Optional<String> key, Optional<String> hint, boolean byAi) {
            key.ifPresent(value -> {
                if (!byAi) {
                    fromRules.add(value);
                }
                inLetter.add(value);
                add(value, hint);
            });
        }

        void typed(Optional<String> key, Optional<String> hint) {
            key.ifPresent(value -> {
                fromRules.add(value);
                add(value, hint);
            });
        }

        private void add(String key, Optional<String> hint) {
            all.add(key);
            hint.ifPresent(value -> hints.putIfAbsent(key, value));
        }

        List<String> kept() {
            return all.stream().limit(MAX_KEYS).toList();
        }

        Set<String> aiOnly() {
            Set<String> only = new HashSet<>(kept());
            only.removeAll(fromRules);
            return only;
        }

        List<AnalysisCompletedEvent.MarkerHint> hints() {
            return kept().stream().map(key -> new AnalysisCompletedEvent.MarkerHint(type, key,
                    inLetter.contains(key) ? SOURCE_LETTER : SOURCE_TYPED, hints.get(key))).toList();
        }

        boolean complete() {
            return all.size() <= MAX_KEYS;
        }
    }
}
