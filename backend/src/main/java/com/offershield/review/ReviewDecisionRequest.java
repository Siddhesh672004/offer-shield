package com.offershield.review;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/reviews/{id}/decision}; the version goes in {@code If-Match}. {@code note} (optional, at
 * most 1,000 characters) is the TPO's note to the student and is included in their email.
 */
public record ReviewDecisionRequest(@NotNull ReviewDecision decision, @Size(max = 1000) String note) {

    @Override
    public String toString() {
        return "ReviewDecisionRequest[decision=" + decision + ", redacted]";
    }
}
