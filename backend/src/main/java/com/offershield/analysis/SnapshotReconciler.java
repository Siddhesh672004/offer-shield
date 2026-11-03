package com.offershield.analysis;

import com.offershield.common.logging.LogContext;
import com.offershield.registry.ReportSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * P13 (silent failure 1): closes the gap between a saved report and the registry's copy of it. If the
 * {@code registry-snapshot} consumer failed on a completion (database down through its retries, so the record went to
 * the DLT, which is never replayed) or lags, "Ask my TPO" and "Report scam" could never find the report. Every
 * {@code offershield.registry.reconcile.interval} (default 2 minutes) this job looks at reports saved between
 * {@value #MAX_AGE_HOURS} hours and {@value #MIN_AGE_MINUTES} minutes ago that have no snapshot, and re-publishes their
 * {@code analysis.completed} from the stored report ({@link AnalysisCompletedPublisher}, the same deterministic event
 * ID, D-28). Consumers deduplicate by that ID, so a repeat changes nothing; the snapshot consumer stores the copy the
 * first time it succeeds. Older reports are left alone (their snapshot may have been cleaned up on purpose). Uses only
 * the analysis' and the registry's own services. Logs counts only (D-27).
 */
@Component
public class SnapshotReconciler {

    static final long MIN_AGE_MINUTES = 2;
    static final long MAX_AGE_HOURS = 24;
    /** At most this many reports are looked at per run; at the scale of one college app that is all of a day's. */
    static final int MAX_PER_RUN = 500;
    private static final Logger log = LoggerFactory.getLogger(SnapshotReconciler.class);

    private final ReportService reports;
    private final ReportSnapshotService snapshots;
    private final AnalysisCompletedPublisher publisher;
    private final boolean enabled;

    public SnapshotReconciler(ReportService reports, ReportSnapshotService snapshots,
                              AnalysisCompletedPublisher publisher,
                              @Value("${offershield.registry.reconcile.enabled:true}") boolean enabled) {
        this.reports = reports;
        this.snapshots = snapshots;
        this.publisher = publisher;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${offershield.registry.reconcile.interval:120s}",
            initialDelayString = "${offershield.registry.reconcile.interval:120s}")
    public void run() {
        if (!enabled) {
            return;
        }
        MDC.put(LogContext.REQUEST_ID, UUID.randomUUID().toString());
        try {
            reconcile(Instant.now());
        } catch (RuntimeException ex) {
            log.warn("snapshot reconcile failed error={}", ex.getClass().getSimpleName());
        } finally {
            MDC.remove(LogContext.REQUEST_ID);
        }
    }

    /** Re-publishes the completion of each report without a snapshot; returns how many were sent. */
    public int reconcile(Instant now) {
        List<UUID> missing = candidates(now);
        int sent = 0;
        for (UUID submissionId : missing) {
            Optional<Completion> completion = reports.findCompletion(submissionId);
            if (completion.isEmpty()) {
                continue; // deleted meanwhile (retention)
            }
            if (!publisher.publish(completion.get())) {
                break; // the broker is down: the next run tries again
            }
            sent++;
        }
        if (!missing.isEmpty()) {
            log.warn("snapshot reconcile missing={} republished={}", missing.size(), sent);
        }
        return sent;
    }

    /** Reports saved in the window without a stored snapshot, oldest first. */
    List<UUID> candidates(Instant now) {
        List<UUID> finished = reports.findIdsFinishedBetween(now.minus(Duration.ofHours(MAX_AGE_HOURS)),
                now.minus(Duration.ofMinutes(MIN_AGE_MINUTES)), MAX_PER_RUN);
        if (finished.isEmpty()) {
            return List.of();
        }
        Set<UUID> stored = snapshots.findExisting(finished);
        return finished.stream().filter(id -> !stored.contains(id)).toList();
    }
}
