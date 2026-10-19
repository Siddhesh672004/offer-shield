package com.offershield.college;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Trimmed on construction so surrounding spaces don't fail {@code @Email}; the auth feature lower-cases it. */
public record AssignTpoRequest(@NotBlank @Email @Size(max = 320) String email) {

    public AssignTpoRequest {
        email = email == null ? null : email.trim();
    }
}
