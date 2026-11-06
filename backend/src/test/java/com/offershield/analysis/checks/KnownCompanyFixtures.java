package com.offershield.analysis.checks;

import com.offershield.company.KnownCompanies;
import com.offershield.company.KnownCompanyDirectory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Known-company lists for the C3/C4 unit tests, built in the test only (nothing is seeded in the database, V-21).
 * The PRD's own example ("Infosys → infosys.com", F8) appears only as a list entry and domain strings (05 §4); letter
 * prose uses fictional companies such as "Northwind Analytics".
 */
public final class KnownCompanyFixtures {

    private KnownCompanyFixtures() {
    }

    public static KnownCompanyDirectory.Company company(String name, String... officialDomains) {
        return new KnownCompanyDirectory.Company(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name,
                List.of(officialDomains));
    }

    /** A mocked {@link KnownCompanies} whose directory holds {@code companies}. */
    public static KnownCompanies knownCompanies(KnownCompanyDirectory.Company... companies) {
        KnownCompanies known = mock(KnownCompanies.class);
        when(known.directory()).thenReturn(new KnownCompanyDirectory(List.of(companies)));
        return known;
    }
}
