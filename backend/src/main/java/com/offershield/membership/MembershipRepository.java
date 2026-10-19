package com.offershield.membership;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserId(UUID userId);

    /** IDOR guard: a TPO only ever loads rows of their own college; anything else looks absent (404). */
    Optional<Membership> findByIdAndCollegeId(UUID id, UUID collegeId);

    /**
     * The college's queue, oldest request first. A PENDING row is only written when it is (re-)requested,
     * so {@code updatedAt} is the time of the current request (a re-apply keeps the original {@code createdAt}).
     */
    @Query("""
            select m from Membership m
            where m.collegeId = :collegeId and m.status = com.offershield.membership.MembershipStatus.PENDING
            order by m.updatedAt asc, m.id asc""")
    List<Membership> findPending(@Param("collegeId") UUID collegeId);
}
