package com.offershield.college;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CollegeRepository extends JpaRepository<College, UUID> {

    /** {@code Containing} escapes LIKE wildcards in {@code name}, so "%" and "_" match literally. */
    List<College> findTop20ByStatusAndNameContainingIgnoreCaseOrderByNameAsc(CollegeStatus status, String name);
}
