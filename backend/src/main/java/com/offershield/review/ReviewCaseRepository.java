package com.offershield.review;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** College-scoped reads for TPOs (PRD §14.3): another college's case looks exactly like a missing one. */
interface ReviewCaseRepository extends JpaRepository<ReviewCase, UUID> {

    boolean existsBySubmissionId(UUID submissionId);

    Optional<ReviewCase> findByIdAndCollegeId(UUID id, UUID collegeId);

    /** {@code SELECT … FOR UPDATE}: a second TPO deciding at the same moment waits, then sees the new version. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReviewCase c where c.id = :id and c.collegeId = :college")
    Optional<ReviewCase> lockByIdAndCollegeId(@Param("id") UUID id, @Param("college") UUID collegeId);

    List<ReviewCase> findByCollegeIdAndStatusOrderByCreatedAtAscIdAsc(UUID collegeId, ReviewStatus status, Limit limit);

    List<ReviewCase> findByStudentUserIdOrderByCreatedAtDesc(UUID studentUserId, Limit limit);

    /** Retention (D-06): which of these submissions have a case that is OPEN or was decided at/after {@code since}. */
    @Query("""
            select distinct c.submissionId from ReviewCase c
            where c.submissionId in :submissionIds
              and (c.status = com.offershield.review.ReviewStatus.OPEN or c.decidedAt >= :since)""")
    List<UUID> findSubmissionsInUse(@Param("submissionIds") Collection<UUID> submissionIds,
                                    @Param("since") Instant since);
}
