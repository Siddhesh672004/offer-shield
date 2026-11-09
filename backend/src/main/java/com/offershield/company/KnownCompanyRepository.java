package com.offershield.company;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface KnownCompanyRepository extends JpaRepository<KnownCompany, UUID> {

    boolean existsByNameIgnoreCase(String name);

    List<KnownCompany> findAllByOrderByNameAsc();

    /** Locks the company row, so two admins changing the same company's domains take turns (no company left empty). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from KnownCompany c where c.id = :id")
    Optional<KnownCompany> findForUpdate(@Param("id") UUID id);
}
