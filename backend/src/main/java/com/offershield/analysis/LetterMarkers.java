package com.offershield.analysis;

import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.registry.MarkerQuery;

import java.util.List;
import java.util.Set;

/**
 * A letter's registry keys (PRD §10.1, F7): the sender's registrable domain (only one that passed the SSRF guard), the
 * HMAC keys of the phone numbers and UPI IDs found (rules, typed fields and the AI's grounded candidates; never the raw
 * values), and the SimHash ({@code null} under 50 words). {@code aiOnlyKeys} are the keys only the AI found (R-A:
 * they only add risk and are labelled). {@code complete} is false when a list hit its cap of
 * {@value RegistryLookup#MAX_KEYS} or the keys could not be computed. {@code hints} (P13, N2) say for each phone / UPI
 * key whether it is in the letter or was only typed, with a masked hint for reviewers. Stored on the report and sent in
 * {@code analysis.completed}; {@link #toString()} shows counts only.
 */
public record LetterMarkers(String domain, List<String> phoneHmacs, List<String> upiHmacs, Long simhash,
                            Set<String> aiOnlyKeys, boolean complete, List<AnalysisCompletedEvent.MarkerHint> hints) {

    /** No keys were computed (a report made without the registry step). */
    public static final LetterMarkers NONE = new LetterMarkers(null, List.of(), List.of(), null, Set.of(), false,
            List.of());

    public LetterMarkers {
        phoneHmacs = phoneHmacs == null ? List.of() : List.copyOf(phoneHmacs);
        upiHmacs = upiHmacs == null ? List.of() : List.copyOf(upiHmacs);
        aiOnlyKeys = aiOnlyKeys == null ? Set.of() : Set.copyOf(aiOnlyKeys);
        hints = hints == null ? List.of() : List.copyOf(hints);
    }

    /** Keys without hints. */
    public LetterMarkers(String domain, List<String> phoneHmacs, List<String> upiHmacs, Long simhash,
                         Set<String> aiOnlyKeys, boolean complete) {
        this(domain, phoneHmacs, upiHmacs, simhash, aiOnlyKeys, complete, List.of());
    }

    /** Only the SSRF-validated sender domain: the other keys could not be computed (P13, silent failure 6). */
    static LetterMarkers domainOnly(String domain) {
        return new LetterMarkers(domain, List.of(), List.of(), null, Set.of(), false, List.of());
    }

    /** A sender domain, phone number or UPI ID to compare exactly (C10). */
    public boolean hasExactKeys() {
        return domain != null || !phoneHmacs.isEmpty() || !upiHmacs.isEmpty();
    }

    public MarkerQuery query() {
        return new MarkerQuery(domain, phoneHmacs, upiHmacs, simhash);
    }

    @Override
    public String toString() {
        return "LetterMarkers[domain=" + (domain != null) + ", phones=" + phoneHmacs.size() + ", upiIds="
                + upiHmacs.size() + ", simhash=" + (simhash != null) + ", complete=" + complete + "]";
    }
}
