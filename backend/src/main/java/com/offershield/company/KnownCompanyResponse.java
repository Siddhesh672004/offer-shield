package com.offershield.company;

import java.util.List;
import java.util.UUID;

/** A known company as the admin screen shows it: name and official domains (registrable, lower-case ASCII, sorted). */
public record KnownCompanyResponse(UUID id, String name, List<String> domains) {

    static KnownCompanyResponse from(KnownCompany company, List<KnownCompanyDomain> domains) {
        return new KnownCompanyResponse(company.getId(), company.getName(),
                domains.stream().map(KnownCompanyDomain::getDomain).sorted().toList());
    }
}
