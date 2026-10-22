package com.offershield.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contract of {@code analysis.completed} v1 (02 §5, D-20), keyed by submission ID and consumed by the
 * {@code submission-status} and {@code registry-snapshot} groups (fan-out). It carries the stored report: no letter
 * text, and evidence is at most 200 characters with emails, phones and UPI IDs masked. The event ID is deterministic
 * (topic + submission ID), so a re-sent completion (D-28) is recognised as a duplicate. Additive changes keep
 * {@code schemaVersion}: P9 added the registry keys (HMACs, SimHash, {@code indicatorsComplete}); an event without them
 * reads as no keys and {@code indicatorsComplete = false}. P13 added {@code senderSource}, {@code letterEmailDomains}
 * and {@code markerHints}; an event without them reads as {@code null} / no hints. A breaking change needs a new topic.
 */
public record AnalysisCompletedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                     String requestId, Payload payload) {

    public static final String TYPE = Topics.ANALYSIS_COMPLETED;
    public static final int SCHEMA_VERSION = 1;
    public static final String READY = "READY";
    public static final String PARTIAL = "PARTIAL";

    /**
     * @param outcome            {@value #READY}, or {@value #PARTIAL} when a check or the AI was unavailable
     * @param band               {@code LOW}, {@code MEDIUM} or {@code HIGH} (PRD §7.2)
     * @param hardOverride       High was forced by C1, C10 or C11
     * @param unavailableChecks  codes of the checks that could not run (e.g. {@code C5})
     * @param senderDomain       the D-33.6 sender domain, only when it passed the SSRF guard
     * @param phoneHmacs         HMAC-SHA256 keys (lower-case hex) of the phone numbers found; never the numbers (P9)
     * @param upiHmacs           HMAC-SHA256 keys of the UPI IDs found (P9)
     * @param simhash            the letter's 64-bit SimHash, {@code null} under 50 words (P9)
     * @param indicatorsComplete every phone number and UPI ID found is in the lists (none dropped at the cap) (P9); a
     *                           wrapper so an event written before P9 (field absent) still reads, as {@code null}
     * @param senderSource       {@code TYPED} or {@code LETTER}: whether the student typed the sender email or it was
     *                           taken from the letter itself (P13, S2); {@code null} without a sender domain
     * @param letterEmailDomains how many distinct registrable email domains the letter itself contains (P13, S2)
     * @param markerHints        per phone / UPI key: typed or in the letter, and a masked hint (P13, N2); never raw
     */
    public record Payload(UUID submissionId, UUID ownerUserId, UUID collegeId, String outcome, int score, String band,
                          boolean hardOverride, List<String> unavailableChecks, List<Signal> signals,
                          String senderDomain, List<String> phoneHmacs, List<String> upiHmacs, Long simhash,
                          Boolean indicatorsComplete, String senderSource, Integer letterEmailDomains,
                          List<MarkerHint> markerHints) {

        public Payload {
            markerHints = markerHints == null ? List.of() : List.copyOf(markerHints);
        }

        /** A payload without the P13 fields (as written before P13). */
        public Payload(UUID submissionId, UUID ownerUserId, UUID collegeId, String outcome, int score, String band,
                       boolean hardOverride, List<String> unavailableChecks, List<Signal> signals, String senderDomain,
                       List<String> phoneHmacs, List<String> upiHmacs, Long simhash, Boolean indicatorsComplete) {
            this(submissionId, ownerUserId, collegeId, outcome, score, band, hardOverride, unavailableChecks, signals,
                    senderDomain, phoneHmacs, upiHmacs, simhash, indicatorsComplete, null, null, List.of());
        }

        /** {@code indicatorsComplete}, with an absent value (before P9) read as {@code false}. */
        public boolean keysComplete() {
            return Boolean.TRUE.equals(indicatorsComplete);
        }
    }

    /** One report signal (PRD §10): outcome TRIGGERED / PASSED / UNAVAILABLE / NOT_APPLICABLE, source kind 1–5. */
    public record Signal(String code, String outcome, int points, boolean hardOverride, String explanation,
                         String evidence, int sourceKind, boolean aiExtracted) {
    }

    /**
     * P13 (N2): what a reviewer may see of one phone / UPI key: {@code type} {@code PHONE} or {@code UPI}, the HMAC
     * {@code key}, {@code source} {@code LETTER} (written in the letter, also when the AI found it there) or
     * {@code TYPED} (only typed by the student), and a masked {@code hint}: "••••1234" (a phone's last 4 digits) or
     * "••••ar@okaxis" (a UPI ID's last 2 handle characters and its provider). Never the raw value.
     */
    public record MarkerHint(String type, String key, String source, String hint) {
    }

    public static AnalysisCompletedEvent of(Payload payload, Instant occurredAt, String requestId) {
        return new AnalysisCompletedEvent(EventIds.of(TYPE, payload.submissionId()), TYPE, SCHEMA_VERSION, occurredAt,
                requestId, payload);
    }
}
