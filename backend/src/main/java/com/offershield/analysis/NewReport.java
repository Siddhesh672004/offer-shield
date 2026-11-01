package com.offershield.analysis;

import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.scoring.Score;
import com.offershield.common.SenderSource;

import java.util.List;
import java.util.UUID;

/**
 * A finished analysis, ready to be saved. {@code senderDomain} is the sender domain only when it passed the SSRF guard
 * (so an IP literal never becomes a registry marker later); {@code partial} is true when a check or the AI was
 * unavailable; {@code aiUsed} says an AI extraction was used, and {@code aiDetails} holds its "AI-extracted" facts.
 * {@code markers} are the letter's registry keys (P9) and {@code reportedBy} the distinct people behind matching
 * unverified markers (C12). {@code senderSource} (TYPED / LETTER, {@code null} without a stored sender domain) and
 * {@code letterEmailDomains} (distinct registrable email domains written in the letter) are P13's S2 facts.
 */
public record NewReport(UUID submissionId, UUID ownerUserId, UUID collegeId, String senderDomain, Score score,
                        boolean partial, List<CheckResult> results, boolean aiUsed, AiDetails aiDetails,
                        LetterMarkers markers, int reportedBy, SenderSource senderSource, Integer letterEmailDomains) {

    public NewReport {
        results = List.copyOf(results);
        aiDetails = aiDetails == null ? AiDetails.NONE : aiDetails;
        markers = markers == null ? LetterMarkers.NONE : markers;
        senderSource = senderDomain == null ? null : senderSource;
        if (reportedBy < 0) {
            throw new IllegalArgumentException("reportedBy must not be negative");
        }
    }

    /** A report without the P13 sender facts. */
    public NewReport(UUID submissionId, UUID ownerUserId, UUID collegeId, String senderDomain, Score score,
                     boolean partial, List<CheckResult> results, boolean aiUsed, AiDetails aiDetails,
                     LetterMarkers markers, int reportedBy) {
        this(submissionId, ownerUserId, collegeId, senderDomain, score, partial, results, aiUsed, aiDetails, markers,
                reportedBy, null, null);
    }

    /** A report made without the registry step. */
    public NewReport(UUID submissionId, UUID ownerUserId, UUID collegeId, String senderDomain, Score score,
                     boolean partial, List<CheckResult> results, boolean aiUsed, AiDetails aiDetails) {
        this(submissionId, ownerUserId, collegeId, senderDomain, score, partial, results, aiUsed, aiDetails,
                LetterMarkers.NONE, 0);
    }

    /** A report made without AI or the registry step. */
    public NewReport(UUID submissionId, UUID ownerUserId, UUID collegeId, String senderDomain, Score score,
                     boolean partial, List<CheckResult> results) {
        this(submissionId, ownerUserId, collegeId, senderDomain, score, partial, results, false, AiDetails.NONE);
    }

    @Override
    public String toString() {
        return "NewReport[submissionId=" + submissionId + ", band=" + score.band() + ", aiUsed=" + aiUsed
                + ", redacted]";
    }
}
