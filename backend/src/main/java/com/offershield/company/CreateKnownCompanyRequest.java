package com.offershield.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A new known company and its official domains (1–10). The name is trimmed with inner spaces collapsed, so the rules
 * apply to what is stored; each domain is checked and reduced to its registrable form by the service.
 */
public record CreateKnownCompanyRequest(
        @NotBlank
        @Size(min = 2, max = 100)
        @Pattern(regexp = KnownCompanyNames.ALLOWED, message = KnownCompanyNames.ALLOWED_MESSAGE)
        String name,
        @NotEmpty
        @Size(max = KnownCompanyService.MAX_DOMAINS)
        List<@NotBlank @Size(max = 253) String> domains) {

    public CreateKnownCompanyRequest {
        name = KnownCompanyNames.clean(name);
    }
}
