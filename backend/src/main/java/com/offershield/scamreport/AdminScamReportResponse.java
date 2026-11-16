package com.offershield.scamreport;

import com.offershield.registry.ReportEvidence;

import java.time.Instant;
import java.util.UUID;

/**
 * One scam report in the admin's queue (PRD §6.4 step 5, D-04): the reporter's description ({@code null} once the
 * retention job wiped it, 30 days after the decision, D-06) and amount asked, the
 * report snapshot's evidence with the markers' status and distinct reporter counts ({@code null} once the snapshot was
 * deleted), whether a PDF link can be made (D-05) and the version to send back as {@code If-Match}. Never the letter
 * text, the HMAC keys or who reported. {@code markersRecorded} (P13, silent failure 7): in the response to a CONFIRM
 * decision, how many scam markers are now confirmed (0 = the letter has nothing the registry can use); 0 otherwise.
 */
public record AdminScamReportResponse(UUID id, UUID submissionId, ScamReportStatus status, String description,
                                      Integer amountAsked, Instant createdAt, Instant decidedAt, long version,
                                      boolean pdfAvailable, ReportEvidence evidence, int markersRecorded) {

    @Override
    public String toString() {
        return "AdminScamReportResponse[id=" + id + ", status=" + status + ", redacted]";
    }
}
