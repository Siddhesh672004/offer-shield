package com.offershield.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One more official domain for an existing known company. */
public record AddKnownCompanyDomainRequest(@NotBlank @Size(max = 253) String domain) {
}
