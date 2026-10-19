package com.offershield.membership;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Body of POST /api/memberships. Trimmed on construction, so the size rules apply to what is stored. */
public record CreateMembershipRequest(
        @NotNull UUID collegeId,
        @NotBlank @Size(max = 50) String rollNo,
        @NotBlank @Size(max = 100) String department) {

    public CreateMembershipRequest {
        rollNo = rollNo == null ? null : rollNo.trim();
        department = department == null ? null : department.trim();
    }
}
