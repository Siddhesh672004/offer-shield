package com.offershield.membership;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/memberships/{id}/decision. {@code reason} is required (non-blank) for REJECT, ignored for APPROVE. */
public record MembershipDecisionRequest(@NotNull MembershipDecision decision, @Size(max = 500) String reason) {
}
