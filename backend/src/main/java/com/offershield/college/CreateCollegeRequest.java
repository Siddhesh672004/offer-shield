package com.offershield.college;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Trimmed on construction, so the size rules apply to what is stored. A blank city becomes {@code null}. */
public record CreateCollegeRequest(
        @NotBlank @Size(min = 2, max = 200) String name,
        @Size(max = 100) String city) {

    public CreateCollegeRequest {
        name = name == null ? null : name.trim();
        city = city == null || city.isBlank() ? null : city.trim();
    }
}
