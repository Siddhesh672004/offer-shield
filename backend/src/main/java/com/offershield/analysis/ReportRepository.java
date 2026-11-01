package com.offershield.analysis;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** IDOR guard (PRD §14.3): another user's report looks exactly like a missing one. */
    @EntityGraph(attributePaths = "signals")
    Optional<Report> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    /** P13: the owner's report exists (the analysis finished), without loading it. */
    boolean existsByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    /** P13 snapshot reconciliation: IDs of reports saved in [from, to), oldest first. */
    @Query("SELECT r.id FROM Report r WHERE r.createdAt >= :from AND r.createdAt < :to ORDER BY r.createdAt ASC")
    List<UUID> findIdsCreatedBetween(@Param("from") Instant from, @Param("to") Instant to, Limit limit);

    /** For re-building {@code analysis.completed} from what was stored (D-28 re-send). */
    @EntityGraph(attributePaths = "signals")
    Optional<Report> findWithSignalsById(UUID id);

    /** "Explain simply": the first saved explanation wins; a concurrent second one changes nothing (0 rows). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Report r SET r.explanation = :explanation WHERE r.id = :id AND r.explanation IS NULL")
    int saveExplanationIfAbsent(@Param("id") UUID id, @Param("explanation") String explanation);

    @Query("SELECT r.explanation FROM Report r WHERE r.id = :id")
    Optional<String> findExplanationById(@Param("id") UUID id);

    /** Retention (D-06): the database deletes the signals with each report (FK {@code ON DELETE CASCADE}, V11). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Report r WHERE r.id IN :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
