package com.offershield.scamreport;

import java.time.Instant;
import java.util.UUID;

/**
 * The reporter's own view of a scam report: its state, never the evidence or the markers. {@code markersRecorded}
 * (P13, silent failure 7) is how many scam markers this report added or re-opened in the shared registry, in the
 * response to {@code POST /api/scam-reports}; 0 there means the letter has nothing the registry can use (a shared
 * domain, no phone number or UPI ID, too short to compare), and it is 0 in every other response.
 */
public record ScamReportResponse(UUID id, UUID submissionId, ScamReportStatus status, Integer amountAsked,
                                 Instant createdAt, Instant decidedAt, int markersRecorded) {

    static ScamReportResponse from(ScamReport report) {
        return from(report, 0);
    }

    static ScamReportResponse from(ScamReport report, int markersRecorded) {
        return new ScamReportResponse(report.getId(), report.getSubmissionId(), report.getStatus(),
                report.getAmountAsked(), report.getCreatedAt(), report.getDecidedAt(), markersRecorded);
    }
}
