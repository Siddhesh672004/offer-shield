package com.offershield.submission;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads select only the columns they need, so listing never loads the encrypted letter text. */
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    @Query("""
            select new com.offershield.submission.StoredRequest(s.id, s.requestSha256)
            from Submission s where s.ownerUserId = :owner and s.idempotencyKey = :key""")
    Optional<StoredRequest> findStoredRequest(@Param("owner") UUID ownerUserId, @Param("key") String idempotencyKey);

    /** The private PDF's public ID, unless none was kept or the submission was deleted (D-05, D-06). */
    @Query("""
            select s.cloudinaryPublicId from Submission s
            where s.id = :id and s.status <> com.offershield.submission.SubmissionStatus.DELETED""")
    Optional<String> findStoredPdf(@Param("id") UUID id);

    long countByOwnerUserIdAndCreatedAtAfter(UUID ownerUserId, Instant since);

    @Query("select min(s.createdAt) from Submission s where s.ownerUserId = :owner and s.createdAt > :since")
    Optional<Instant> findOldestCreatedAtAfter(@Param("owner") UUID ownerUserId, @Param("since") Instant since);

    /** IDOR guard (PRD §14.3): another user's submission looks exactly like a missing one. */
    @Query("""
            select new com.offershield.submission.SubmissionView(s.id, s.status, s.sourceType, s.collegeId,
                s.cloudinaryPublicId, s.createdAt, s.updatedAt, s.expiresAt)
            from Submission s where s.id = :id and s.ownerUserId = :owner""")
    Optional<SubmissionView> findViewByIdAndOwnerUserId(@Param("id") UUID id, @Param("owner") UUID ownerUserId);

    @Query("""
            select new com.offershield.submission.SubmissionView(s.id, s.status, s.sourceType, s.collegeId,
                s.cloudinaryPublicId, s.createdAt, s.updatedAt, s.expiresAt)
            from Submission s
            where s.ownerUserId = :owner and s.status <> com.offershield.submission.SubmissionStatus.DELETED
            order by s.createdAt desc, s.id desc""")
    List<SubmissionView> findViewsByOwner(@Param("owner") UUID ownerUserId, Limit limit);

    /** QUEUED rows not published since {@code cutoff}, oldest first (the sweeper filters by each row's backoff). */
    @Query("""
            select new com.offershield.submission.SweepCandidate(s.id, s.ownerUserId, s.collegeId, s.sourceType,
                s.contentSha256, s.createdAt, s.publishAttempts, s.lastPublishedAt)
            from Submission s
            where s.status = com.offershield.submission.SubmissionStatus.QUEUED and s.lastPublishedAt <= :cutoff
            order by s.lastPublishedAt asc, s.id asc""")
    List<SweepCandidate> findSweepCandidates(@Param("cutoff") Instant cutoff, Limit limit);

    /** Compare-and-set, so a row that became READY meanwhile, or another sweep, is left alone (returns 0). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Submission s set s.publishAttempts = s.publishAttempts + 1, s.lastPublishedAt = :now
            where s.id = :id and s.status = com.offershield.submission.SubmissionStatus.QUEUED
              and s.publishAttempts = :attempts""")
    int recordRepublish(@Param("id") UUID id, @Param("attempts") int expectedAttempts, @Param("now") Instant now);

    /** QUEUED (or FAILED: a late completion) → READY / PARTIAL; any other status is left alone (returns 0). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Submission s set s.status = :outcome
            where s.id = :id and s.status in (com.offershield.submission.SubmissionStatus.QUEUED,
                                              com.offershield.submission.SubmissionStatus.FAILED)""")
    int markAnalysed(@Param("id") UUID id, @Param("outcome") SubmissionStatus outcome);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Submission s set s.status = com.offershield.submission.SubmissionStatus.FAILED
            where s.id = :id and s.status = com.offershield.submission.SubmissionStatus.QUEUED
              and s.publishAttempts = :attempts""")
    int markFailed(@Param("id") UUID id, @Param("attempts") int expectedAttempts);
}
