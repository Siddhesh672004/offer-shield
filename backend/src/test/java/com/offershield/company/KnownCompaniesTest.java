package com.offershield.company;

import com.offershield.support.NoOpTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The analysis reads the known-company list from memory: one load (two queries in one read-only transaction) serves
 * every check of every letter until an admin change evicts it, or at the latest after the TTL (a safety net for rows
 * written outside the API, e.g. a later SQL seed step).
 */
class KnownCompaniesTest {

    private final KnownCompanyRepository companies = mock(KnownCompanyRepository.class);
    private final KnownCompanyDomainRepository domains = mock(KnownCompanyDomainRepository.class);
    private final NoOpTransactionManager transactions = new NoOpTransactionManager();
    private final AtomicLong nanos = new AtomicLong();
    private final KnownCompanies known = new KnownCompanies(companies, domains, transactions, Duration.ofMinutes(10),
            nanos::get);

    @Test
    void directory_groupsSortedDomains_skipsCompanyWithoutDomains() {
        KnownCompany zenvora = KnownCompany.create("Zenvora");
        KnownCompany empty = KnownCompany.create("Empty Co");
        KnownCompany northwind = KnownCompany.create("Northwind Analytics");
        when(companies.findAllByOrderByNameAsc()).thenReturn(List.of(empty, northwind, zenvora));
        when(domains.findAllByOrderByDomainAsc()).thenReturn(List.of(
                KnownCompanyDomain.create(northwind.getId(), "northwind-analytics.com"),
                KnownCompanyDomain.create(northwind.getId(), "northwind.co.in"),
                KnownCompanyDomain.create(zenvora.getId(), "zenvora.com")));

        KnownCompanyDirectory directory = known.directory();

        assertThat(directory.companies()).extracting(KnownCompanyDirectory.Company::name)
                .containsExactly("Northwind Analytics", "Zenvora");
        assertThat(directory.companies().get(0).officialDomains())
                .containsExactly("northwind-analytics.com", "northwind.co.in");
        assertThat(directory.ownerOf("NORTHWIND.CO.IN")).get().extracting(KnownCompanyDirectory.Company::id)
                .isEqualTo(northwind.getId());
        assertThat(known.isOfficialDomain("zenvora.com")).isTrue();
        assertThat(known.isOfficialDomain("zenvora.co.in")).isFalse();
        assertThat(known.isOfficialDomain(null)).isFalse();
        assertThat(transactions.commits()).as("both queries in one read-only transaction").isEqualTo(1);
    }

    @Test
    void cached_noSecondQuery() {
        when(companies.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(domains.findAllByOrderByDomainAsc()).thenReturn(List.of());

        known.directory();
        known.directory();
        known.isOfficialDomain("zenvora.com");

        verify(companies, times(1)).findAllByOrderByNameAsc();
        verify(domains, times(1)).findAllByOrderByDomainAsc();
        assertThat(known.directory().isEmpty()).isTrue();
    }

    @Test
    void evict_nextReadReloads() {
        when(companies.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(domains.findAllByOrderByDomainAsc()).thenReturn(List.of());
        known.directory();

        known.evict();
        known.directory();

        verify(companies, times(2)).findAllByOrderByNameAsc();
    }

    @Test
    void evictInsideTransaction_againAfterCommit() {
        when(companies.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(domains.findAllByOrderByDomainAsc()).thenReturn(List.of());

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            known.evict();
            known.directory(); // a concurrent reader caching the not-yet-committed (old) list
        });
        known.directory();

        verify(companies, times(2)).findAllByOrderByNameAsc();
    }

    @Test
    void expiresAfterTtl() {
        when(companies.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(domains.findAllByOrderByDomainAsc()).thenReturn(List.of());
        known.directory();

        nanos.addAndGet(Duration.ofMinutes(10).plusSeconds(1).toNanos());
        known.directory();

        verify(companies, times(2)).findAllByOrderByNameAsc();
    }

    @Test
    void loadFails_exceptionPropagates_nothingCached() {
        when(companies.findAllByOrderByNameAsc()).thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of());
        when(domains.findAllByOrderByDomainAsc()).thenReturn(List.of());

        assertThatThrownBy(known::directory).isInstanceOf(IllegalStateException.class);
        assertThat(known.directory().isEmpty()).isTrue();
    }
}
