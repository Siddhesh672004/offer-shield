package com.offershield.registry;

import com.offershield.common.events.AnalysisCompletedEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The registry's copy of a finished report (D-04): what a TPO or the admin sees as evidence, and the source of the
 * markers when its scam is reported or confirmed (P10). {@code domain} is the sender's registrable domain (it passed the
 * SSRF guard); phone numbers and UPI IDs are present only as HMAC keys; {@code signals} carry masked evidence.
 * {@code indicatorsComplete} is false when the analysis could not keep every marker (a list hit its cap) or the
 * report predates P9. P13: {@code senderSource} ({@code TYPED} / {@code LETTER}, {@code null} without a domain or
 * before P13), {@code letterEmailDomains} (distinct registrable email domains in the letter, {@code null} before P13)
 * and {@code markerHints} (per phone / UPI key: typed or in the letter, and a masked hint). {@link #toString()} shows
 * IDs and the band only.
 */
public record ReportSnapshotView(UUID submissionId, UUID ownerUserId, UUID collegeId, String band, int score,
                                 boolean hardOverride, boolean partial, String domain, List<String> phoneHmacs,
                                 List<String> upiHmacs, Long simhash, List<AnalysisCompletedEvent.Signal> signals,
                                 boolean indicatorsComplete, Instant completedAt, String senderSource,
                                 Integer letterEmailDomains, List<AnalysisCompletedEvent.MarkerHint> markerHints) {

    public ReportSnapshotView {
        phoneHmacs = phoneHmacs == null ? List.of() : List.copyOf(phoneHmacs);
        upiHmacs = upiHmacs == null ? List.of() : List.copyOf(upiHmacs);
        signals = signals == null ? List.of() : List.copyOf(signals);
        markerHints = markerHints == null ? List.of() : List.copyOf(markerHints);
    }

    /** A snapshot without the P13 fields (as stored before P13). */
    public ReportSnapshotView(UUID submissionId, UUID ownerUserId, UUID collegeId, String band, int score,
                              boolean hardOverride, boolean partial, String domain, List<String> phoneHmacs,
                              List<String> upiHmacs, Long simhash, List<AnalysisCompletedEvent.Signal> signals,
                              boolean indicatorsComplete, Instant completedAt) {
        this(submissionId, ownerUserId, collegeId, band, score, hardOverride, partial, domain, phoneHmacs, upiHmacs,
                simhash, signals, indicatorsComplete, completedAt, null, null, List.of());
    }

    @Override
    public String toString() {
        return "ReportSnapshotView[submissionId=" + submissionId + ", band=" + band + ", redacted]";
    }
}
