package com.offershield.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface KnownCompanyDomainRepository extends JpaRepository<KnownCompanyDomain, UUID> {

    boolean existsByDomain(String domain);

    long countByCompanyId(UUID companyId);

    Optional<KnownCompanyDomain> findByCompanyIdAndDomain(UUID companyId, String domain);

    List<KnownCompanyDomain> findByCompanyIdOrderByDomainAsc(UUID companyId);

    List<KnownCompanyDomain> findAllByOrderByDomainAsc();
}
