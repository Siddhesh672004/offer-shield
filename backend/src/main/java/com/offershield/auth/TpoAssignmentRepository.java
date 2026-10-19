package com.offershield.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TpoAssignmentRepository extends JpaRepository<TpoAssignment, UUID> {

    Optional<TpoAssignment> findByEmail(String email);

    /**
     * Deletes the assignment we read, if it still exists for the same college. Returns 1 only for the one sign-in
     * that won a race; 0 if another sign-in consumed it or an admin changed it in the meantime.
     */
    @Modifying
    @Query("DELETE FROM TpoAssignment a WHERE a.id = :id AND a.collegeId = :collegeId")
    int consume(@Param("id") UUID id, @Param("collegeId") UUID collegeId);
}
