package com.offershield.scamreport;

import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/admin/scam-reports/{id}/decision}; the version goes in {@code If-Match}. */
public record ScamReportDecisionRequest(@NotNull ScamReportDecision decision) {
}
