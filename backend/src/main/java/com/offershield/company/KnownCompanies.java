package com.offershield.company;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The known-company list as other features read it (the analysis' C3/C4, from P9 the registry's shared-domain guard,
 * D-08), kept in memory (PRD §13.7, Caffeine): one load — two queries in one read-only transaction — serves every check
 * of every letter until an admin change evicts it ({@link KnownCompanyService}), or at the latest after
 * {@value #TTL_MINUTES} minutes (a safety net for rows written outside the API, such as a later SQL seed step). A failed
 * load is not cached: the exception reaches the check, which the analysis turns into UNAVAILABLE.
 */
@Component
public class KnownCompanies {

    static final long TTL_MINUTES = 10;
    private static final String ALL = "all";

    private final KnownCompanyRepository companies;
    private final KnownCompanyDomainRepository domains;
    private final TransactionTemplate readOnly;
    private final Cache<String, KnownCompanyDirectory> cache;

    @Autowired
    KnownCompanies(KnownCompanyRepository companies, KnownCompanyDomainRepository domains,
                   PlatformTransactionManager transactionManager) {
        this(companies, domains, transactionManager, Duration.ofMinutes(TTL_MINUTES), Ticker.systemTicker());
    }

    KnownCompanies(KnownCompanyRepository companies, KnownCompanyDomainRepository domains,
                   PlatformTransactionManager transactionManager, Duration ttl, Ticker ticker) {
        this.companies = companies;
        this.domains = domains;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(1).ticker(ticker).build();
    }

    /** The current list: companies by name, each with at least one official domain. */
    public KnownCompanyDirectory directory() {
        return cache.get(ALL, key -> load());
    }

    /** D-08: true when {@code registrableDomain} is an official domain of any known company. */
    public boolean isOfficialDomain(String registrableDomain) {
        return directory().isOfficialDomain(registrableDomain);
    }

    /**
     * Drops the cached list now and, inside a transaction, again once it completes: until the commit a concurrent
     * reader can still load the old rows and would otherwise keep them for the whole TTL.
     */
    void evict() {
        cache.invalidateAll();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cache.invalidateAll();
                }
            });
        }
    }

    private KnownCompanyDirectory load() {
        return readOnly.execute(status -> {
            Map<UUID, List<String>> byCompany = new LinkedHashMap<>();
            for (KnownCompanyDomain domain : domains.findAllByOrderByDomainAsc()) {
                byCompany.computeIfAbsent(domain.getCompanyId(), id -> new ArrayList<>()).add(domain.getDomain());
            }
            List<KnownCompanyDirectory.Company> listed = new ArrayList<>();
            for (KnownCompany company : companies.findAllByOrderByNameAsc()) {
                List<String> official = byCompany.get(company.getId());
                if (official != null) {
                    listed.add(new KnownCompanyDirectory.Company(company.getId(), company.getName(), official));
                }
            }
            return new KnownCompanyDirectory(listed);
        });
    }
}
