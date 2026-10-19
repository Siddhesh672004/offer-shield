package com.offershield.membership;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Body of POST /api/memberships. Roll number and department are stored trimmed. */
public record CreateMembershipRequest(
        @NotNull UUID collegeId,
        @NotBlank @Size(max = 50) String rollNo,
        @NotBlank @Size(max = 100) String department) {
}
