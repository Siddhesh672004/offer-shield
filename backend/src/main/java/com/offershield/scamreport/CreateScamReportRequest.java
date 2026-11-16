package com.offershield.scamreport;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /api/scam-reports} (PRD §6.4 step 1): which of the user's own submissions, what happened (at
 * most 2,000 characters) and the amount asked in rupees, if any. {@link #toString()} never shows the description.
 */
public record CreateScamReportRequest(@NotNull UUID submissionId,
                                      @NotBlank @Size(max = 2000) String description,
                                      @PositiveOrZero @Max(100_000_000) Integer amountAsked) {

    @Override
    public String toString() {
        return "CreateScamReportRequest[submissionId=" + submissionId + ", redacted]";
    }
}
