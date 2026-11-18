package com.offershield.review;

import com.offershield.registry.ReportEvidence;

import java.time.Instant;
import java.util.UUID;

/**
 * A review case as the TPO of its college sees it (PRD §6.5, D-04): who is asking (name and email), the state, the
 * report snapshot's evidence ({@code null} once deleted), whether a PDF link can be made (D-05) and the version to send
 * back as {@code If-Match}. Never the letter text. {@code markersRecorded} (P13, silent failure 7): in the response to
 * a CONFIRMED_SCAM decision, how many scam markers are now confirmed (0 = the letter has nothing the registry can
 * use); 0 otherwise.
 */
public record ReviewCaseDetail(UUID id, UUID submissionId, ReviewStatus status, String note, String studentName,
                               String studentEmail, Instant createdAt, Instant decidedAt, long version,
                               boolean pdfAvailable, ReportEvidence evidence, int markersRecorded) {

    @Override
    public String toString() {
        return "ReviewCaseDetail[id=" + id + ", status=" + status + ", redacted]";
    }
}
