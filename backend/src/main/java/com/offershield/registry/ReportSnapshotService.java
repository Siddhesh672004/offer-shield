package com.offershield.registry;

import com.offershield.analysis.lookup.DomainValidator;
import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.common.web.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns {@code report_snapshot} (D-04, D-20): the {@code registry-snapshot} consumer stores each finished report here
 * (upsert by submission ID, deduplicated by {@code processed_event} in the same transaction, D-28), and the review and
 * scam-report features (P10) read it as evidence and as the source of a report's markers. The sender domain is kept as
 * its registrable domain, and only when it passes the SSRF guard again. The retention job (P11, D-06) deletes a
 * snapshot after the letter's 30 days, once no open case or scam report still needs it.
 */
@Service
public class ReportSnapshotService {

    /** The consumer group of {@code analysis.completed} that keeps snapshots, also its {@code processed_event} name. */
    public static final String CONSUMER = "registry-snapshot";
    private static final Logger log = LoggerFactory.getLogger(ReportSnapshotService.class);

    private final ReportSnapshotRepository snapshots;
    private final ProcessedEvents processedEvents;
    private final DomainValidator domainValidator;

    public ReportSnapshotService(ReportSnapshotRepository snapshots, ProcessedEvents processedEvents,
                                 DomainValidator domainValidator) {
        this.snapshots = snapshots;
        this.processedEvents = processedEvents;
        this.domainValidator = domainValidator;
    }

    /** @return {@code false} when the event was already processed (a duplicate delivery) */
    @Transactional
    public boolean store(UUID eventId, AnalysisCompletedEvent.Payload payload, Instant completedAt) {
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            log.info("snapshot skipped: duplicate event");
            return false;
        }
        String domain = payload.senderDomain() == null ? null
                : domainValidator.validate(payload.senderDomain()).map(ValidatedDomain::registrableDomain).orElse(null);
        snapshots.upsert(new ReportSnapshotView(payload.submissionId(), payload.ownerUserId(), payload.collegeId(),
                payload.band(), payload.score(), payload.hardOverride(),
                AnalysisCompletedEvent.PARTIAL.equals(payload.outcome()), domain, payload.phoneHmacs(),
                payload.upiHmacs(), payload.simhash(), payload.signals(), payload.keysComplete(), completedAt,
                domain == null ? null : payload.senderSource(), payload.letterEmailDomains(), payload.markerHints()));
        log.info("snapshot stored band={}", payload.band());
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<ReportSnapshotView> find(UUID submissionId) {
        return snapshots.find(submissionId);
    }

    /** P13 (silent failure 1): the IDs among {@code submissionIds} whose snapshot is stored. */
    @Transactional(readOnly = true)
    public Set<UUID> findExisting(Collection<UUID> submissionIds) {
        return submissionIds.isEmpty() ? Set.of() : snapshots.existing(submissionIds);
    }

    /**
     * P13 (silent failure 1): the 409 for an owner whose finished report has no snapshot yet (the
     * {@code registry-snapshot} consumer lags or a re-send is on its way). Callers check ownership first, so anyone else
     * keeps getting the same 404 as for a random ID.
     */
    public static ConflictException notReady() {
        return new ConflictException("REPORT_NOT_READY", "This report is still being prepared. Try again in a minute.");
    }

    /** The snapshot only when {@code ownerUserId} owns it (PRD §14.3: another user's looks exactly like a missing one). */
    @Transactional(readOnly = true)
    public Optional<ReportSnapshotView> findForOwner(UUID submissionId, UUID ownerUserId) {
        return snapshots.find(submissionId).filter(snapshot -> snapshot.ownerUserId().equals(ownerUserId));
    }

    /**
     * 30-day retention (D-06): the submission IDs of stored snapshots after {@code after}, in ID order. The table stays
     * small (about 30 days of letters plus those an open case still needs), so the job pages through all of it.
     */
    @Transactional(readOnly = true)
    public List<UUID> findSubmissionIds(UUID after, int limit) {
        return snapshots.findSubmissionIds(after, limit);
    }

    /**
     * 30-day retention (D-06): deletes these snapshots. The caller has checked that no OPEN review case or scam report
     * (or one decided in the last 30 days) still needs them; the markers made from them stay in the registry.
     */
    @Transactional
    public int delete(Collection<UUID> submissionIds) {
        return submissionIds.isEmpty() ? 0 : snapshots.delete(submissionIds);
    }
}
