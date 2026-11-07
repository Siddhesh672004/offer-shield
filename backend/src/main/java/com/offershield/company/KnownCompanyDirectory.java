package com.offershield.company;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A read-only snapshot of the known-company list (PRD §4 F8, D-24) for the analysis (C3, C4) and, from P9, the
 * registry's shared-domain guard (D-08). Every company in it has at least one official domain; each official domain is
 * a registrable domain in lower-case ASCII ({@code infosys.com}) and belongs to one company only.
 */
public final class KnownCompanyDirectory {

    public static final KnownCompanyDirectory EMPTY = new KnownCompanyDirectory(List.of());

    private final List<Company> companies;
    private final Map<String, Company> byDomain;

    public KnownCompanyDirectory(List<Company> companies) {
        this.companies = List.copyOf(companies);
        Map<String, Company> owners = new HashMap<>();
        for (Company company : this.companies) {
            for (String domain : company.officialDomains()) {
                owners.putIfAbsent(domain, company);
            }
        }
        this.byDomain = Map.copyOf(owners);
    }

    /** The companies, ordered by name. */
    public List<Company> companies() {
        return companies;
    }

    public boolean isEmpty() {
        return companies.isEmpty();
    }

    /** The company that lists {@code registrableDomain} (any case) as an official domain. */
    public Optional<Company> ownerOf(String registrableDomain) {
        return registrableDomain == null ? Optional.empty()
                : Optional.ofNullable(byDomain.get(registrableDomain.strip().toLowerCase(Locale.ROOT)));
    }

    /** D-08: true when {@code registrableDomain} is any known company's official domain. */
    public boolean isOfficialDomain(String registrableDomain) {
        return ownerOf(registrableDomain).isPresent();
    }

    /** One known company: its display name and its official domains (registrable, lower-case ASCII, sorted). */
    public record Company(UUID id, String name, List<String> officialDomains) {

        public Company {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            officialDomains = List.copyOf(officialDomains);
            if (officialDomains.isEmpty()) {
                throw new IllegalArgumentException("a known company needs at least one official domain");
            }
        }

        public boolean owns(String registrableDomain) {
            return registrableDomain != null
                    && officialDomains.contains(registrableDomain.strip().toLowerCase(Locale.ROOT));
        }
    }
}
