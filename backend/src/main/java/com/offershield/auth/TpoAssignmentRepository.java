package com.offershield.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TpoAssignmentRepository extends JpaRepository<TpoAssignment, UUID> {

    Optional<TpoAssignment> findByEmail(String email);
}
