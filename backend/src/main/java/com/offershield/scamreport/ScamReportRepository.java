package com.offershield.scamreport;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ScamReportRepository extends JpaRepository<ScamReport, UUID> {

    boolean existsBySubmissionIdAndReporterUserId(UUID submissionId, UUID reporterUserId);

    long countByReporterUserIdAndCreatedAtAfter(UUID reporterUserId, Instant since);

    @Query("select min(r.createdAt) from ScamReport r where r.reporterUserId = :reporter and r.createdAt > :since")
    Optional<Instant> findOldestCreatedAtAfter(@Param("reporter") UUID reporterUserId, @Param("since") Instant since);

    /** {@code SELECT … FOR UPDATE}: a second admin deciding at the same moment waits, then sees the new version. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ScamReport r where r.id = :id")
    Optional<ScamReport> lockById(@Param("id") UUID id);

    List<ScamReport> findByReporterUserIdOrderByCreatedAtDesc(UUID reporterUserId, Limit limit);

    List<ScamReport> findByStatusOrderByCreatedAtAscIdAsc(ScamReportStatus status, Limit limit);

    List<ScamReport> findByStatusOrderByDecidedAtDescIdDesc(ScamReportStatus status, Limit limit);

    /** Retention (D-06): which of these submissions have a report that is OPEN or was decided at/after {@code since}. */
    @Query("""
            select distinct r.submissionId from ScamReport r
            where r.submissionId in :submissionIds
              and (r.status = com.offershield.scamreport.ScamReportStatus.OPEN or r.decidedAt >= :since)""")
    List<UUID> findSubmissionsInUse(@Param("submissionIds") Collection<UUID> submissionIds,
                                    @Param("since") Instant since);

    /** Retention (D-06): decided reports whose description is still stored and was decided before {@code before}. */
    @Query("""
            select r.id from ScamReport r
            where r.descriptionCipher is not null and r.status <> com.offershield.scamreport.ScamReportStatus.OPEN
              and r.decidedAt < :before
            order by r.id""")
    List<UUID> findDescriptionsDecidedBefore(@Param("before") Instant before, Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ScamReport r set r.descriptionCipher = null where r.id in :ids")
    int wipeDescriptions(@Param("ids") Collection<UUID> ids);
}
