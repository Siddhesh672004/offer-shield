package com.offershield.retention;

import com.offershield.ai.LlmCache;
import com.offershield.analysis.ReportService;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.common.logging.LogContext;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.review.ReviewService;
import com.offershield.scamreport.ScamReportService;
import com.offershield.submission.ExpiredSubmission;
import com.offershield.submission.SubmissionRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The 30-day cleanup (P11-01; D-06; PRD F10, §6.3, §9.1, §14.6), run daily by {@link RetentionJob} or on demand.
 * Each step works in small batches, each batch in its own short transaction, and is safe to run again:
 * <ol>
 *   <li><b>Submissions</b> whose 30 days are over: the private PDF is deleted from Cloudinary <i>outside</i> any
 *       transaction; then one transaction per batch deletes their reports (signals and saved explanation go with
 *       them) and wipes the rows (text, contacts, sender domain; status DELETED). The PDF's public ID is cleared only
 *       after Cloudinary confirmed, so a failed delete is retried by the next run; after 3 failures in a row the rest
 *       of the run leaves the file store alone. The letter text is wiped on time either way.</li>
 *   <li><b>Report snapshots</b> of expired submissions, unless a review case or scam report on that letter is OPEN or
 *       was decided less than 30 days ago (D-06: an open case still needs the evidence).</li>
 *   <li><b>Scam-report descriptions</b> 30 days after the admin's decision (the report row and decision stay).</li>
 *   <li><b>AI-cache rows</b> older than 30 days and <b>{@code processed_event} rows</b> older than 14 days.</li>
 * </ol>
 * Hashed indicators, their reporters, review cases and the audit log are kept (PRD §14.6). A step that fails is
 * logged by exception class and the other steps still run; everything committed so far stays, and the next run picks
 * up the rest. A submission or snapshot batch whose transaction fails is skipped and counted (P13), so paging goes on
 * past it instead of the same batch stopping its step every day. Only one run at a time (in this JVM; the app runs as a single instance). Logs carry counts and IDs only
 * (D-27). Call {@link #run} outside any transaction.
 */
@Service
public class RetentionService {

    /** D-06: case evidence stays while a case is open and for 30 days after its decision. */
    static final Duration DECISION_HOLD = Duration.ofDays(30);
    static final Duration LLM_CACHE_AGE = Duration.ofDays(30);
    /** Well beyond Kafka's retention (3 days, DLTs 7), so no redelivery can need a deleted dedupe row. */
    static final Duration PROCESSED_EVENT_AGE = Duration.ofDays(14);
    static final int SUBMISSION_BATCH = 50;
    static final int SNAPSHOT_BATCH = 200;
    static final int DELETE_BATCH = 1000;
    static final int MAX_PDF_FAILURES_IN_A_ROW = 3;
    /** The smallest {@code BINARY(16)} key: keyset paging starts after it. */
    static final UUID FIRST_ID = new UUID(0L, 0L);
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final ReentrantLock running = new ReentrantLock();
    private final SubmissionRetention submissions;
    private final ReportService reports;
    private final ReportSnapshotService snapshots;
    private final ReviewService reviews;
    private final ScamReportService scamReports;
    private final LlmCache llmCache;
    private final ProcessedEvents processedEvents;
    private final TransactionTemplate transaction;

    @SuppressWarnings("java:S107")
    public RetentionService(SubmissionRetention submissions, ReportService reports, ReportSnapshotService snapshots,
                            ReviewService reviews, ScamReportService scamReports, LlmCache llmCache,
                            ProcessedEvents processedEvents, PlatformTransactionManager transactionManager) {
        this.submissions = submissions;
        this.reports = reports;
        this.snapshots = snapshots;
        this.reviews = reviews;
        this.scamReports = scamReports;
        this.llmCache = llmCache;
        this.processedEvents = processedEvents;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * One cleanup run as of {@code now}.
     *
     * @return what it did, or empty when another run is still going (that run does the work)
     */
    public Optional<RetentionResult> run(Instant now) {
        if (!running.tryLock()) {
            log.info("retention run skipped: another run is still going");
            return Optional.empty();
        }
        try {
            long start = System.nanoTime();
            Counts counts = new Counts();
            step("submissions", counts, () -> wipeSubmissions(now, counts));
            step("snapshots", counts, () -> deleteSnapshots(now, counts));
            step("descriptions", counts, () -> inBatches(
                    () -> scamReports.wipeDescriptionsDecidedBefore(now.minus(DECISION_HOLD), DELETE_BATCH),
                    wiped -> counts.descriptionsWiped += wiped));
            step("llmCache", counts, () -> inBatches(
                    () -> llmCache.deleteCreatedBefore(now.minus(LLM_CACHE_AGE), DELETE_BATCH),
                    deleted -> counts.llmCacheDeleted += deleted));
            step("processedEvents", counts, () -> inBatches(
                    () -> processedEvents.deleteProcessedBefore(now.minus(PROCESSED_EVENT_AGE), DELETE_BATCH),
                    deleted -> counts.processedEventsDeleted += deleted));
            RetentionResult result = counts.toResult();
            log.info("retention run: submissionsWiped={} reportsDeleted={} pdfsDeleted={} pdfDeleteFailures={} "
                            + "snapshotsDeleted={} snapshotsKept={} descriptionsWiped={} llmCacheDeleted={} "
                            + "processedEventsDeleted={} failedSteps={} failedBatches={} durationMs={}",
                    result.submissionsWiped(), result.reportsDeleted(), result.pdfsDeleted(),
                    result.pdfDeleteFailures(), result.snapshotsDeleted(), result.snapshotsKept(),
                    result.descriptionsWiped(), result.llmCacheDeleted(), result.processedEventsDeleted(),
                    result.failedSteps(), result.failedBatches(), (System.nanoTime() - start) / 1_000_000);
            if (result.pdfDeleteFailures() > 0) {
                log.warn("retention: {} PDF(s) not deleted from file storage; the next run tries again",
                        result.pdfDeleteFailures());
            }
            return Optional.of(result);
        } finally {
            running.unlock();
        }
    }

    private void wipeSubmissions(Instant now, Counts counts) {
        UUID after = FIRST_ID;
        int pdfFailuresInARow = 0;
        List<ExpiredSubmission> batch;
        do {
            batch = submissions.findExpired(now, after, SUBMISSION_BATCH);
            if (batch.isEmpty()) {
                return;
            }
            after = batch.getLast().id();
            // External calls first, with no database transaction open (each can take up to the 15 s timeout).
            List<UUID> pdfsGone = new ArrayList<>();
            for (ExpiredSubmission row : batch) {
                if (row.cloudinaryPublicId() == null) {
                    continue;
                }
                if (pdfFailuresInARow >= MAX_PDF_FAILURES_IN_A_ROW) {
                    counts.pdfDeleteFailures++; // file storage looks down: leave it for the next run
                    continue;
                }
                if (deletePdf(row)) {
                    pdfsGone.add(row.id());
                    counts.pdfsDeleted++;
                    pdfFailuresInARow = 0;
                } else {
                    counts.pdfDeleteFailures++;
                    pdfFailuresInARow++;
                }
            }
            List<UUID> ids = batch.stream().map(ExpiredSubmission::id).toList();
            inOwnTransaction("submissions", counts, () -> {
                int reportsDeleted = reports.deleteForSubmissions(ids);
                return new BatchCounts(reportsDeleted, submissions.wipe(ids, pdfsGone, now));
            }).ifPresent(done -> {
                counts.reportsDeleted += done.first();
                counts.submissionsWiped += done.second();
            });
        } while (batch.size() == SUBMISSION_BATCH);
    }

    /**
     * Runs one batch in its own short transaction. A batch that fails is rolled back, logged by exception class,
     * counted in {@code failedBatches} and skipped (P13): paging goes on after it, so one batch that always fails cannot
     * stop the rest of its step every day; the next run tries it again.
     *
     * @return the batch's counts once it committed, or empty when it failed
     */
    private Optional<BatchCounts> inOwnTransaction(String step, Counts counts, Supplier<BatchCounts> batch) {
        try {
            return Optional.ofNullable(transaction.execute(status -> batch.get()));
        } catch (RuntimeException ex) {
            counts.failedBatches++;
            log.warn("retention batch skipped step={} error={}", step, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Two counts of one committed batch (submissions: reports deleted, rows wiped; snapshots: kept, deleted). */
    private record BatchCounts(int first, int second) {
    }

    private boolean deletePdf(ExpiredSubmission row) {
        MDC.put(LogContext.SUBMISSION_ID, row.id().toString());
        try {
            return submissions.deletePdf(row.cloudinaryPublicId());
        } finally {
            MDC.remove(LogContext.SUBMISSION_ID);
        }
    }

    private void deleteSnapshots(Instant now, Counts counts) {
        Instant decidedSince = now.minus(DECISION_HOLD);
        UUID after = FIRST_ID;
        List<UUID> page;
        do {
            page = snapshots.findSubmissionIds(after, SNAPSHOT_BATCH);
            if (page.isEmpty()) {
                return;
            }
            after = page.getLast();
            List<UUID> ids = page;
            // The holds are read in the same short transaction as the delete.
            inOwnTransaction("snapshots", counts, () -> {
                Set<UUID> expired = submissions.expiredAmong(ids, now);
                if (expired.isEmpty()) {
                    return new BatchCounts(0, 0);
                }
                Set<UUID> needed = new HashSet<>(reviews.stillNeedEvidence(expired, decidedSince));
                needed.addAll(scamReports.stillNeedEvidence(expired, decidedSince));
                List<UUID> unneeded = ids.stream()
                        .filter(id -> expired.contains(id) && !needed.contains(id))
                        .toList();
                return new BatchCounts(needed.size(), snapshots.delete(unneeded));
            }).ifPresent(done -> {
                counts.snapshotsKept += done.first();
                counts.snapshotsDeleted += done.second();
            });
        } while (page.size() == SNAPSHOT_BATCH);
    }

    /** Repeats a bounded delete (each call is its own short statement or transaction) until a batch is not full. */
    private static void inBatches(IntSupplier deleteBatch, IntConsumer count) {
        int deleted;
        do {
            deleted = deleteBatch.getAsInt();
            count.accept(deleted);
        } while (deleted >= DELETE_BATCH);
    }

    private static void step(String name, Counts counts, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            counts.failedSteps++;
            log.warn("retention step failed step={} error={}", name, ex.getClass().getSimpleName());
        }
    }

    /** Running totals of one run (one thread). */
    private static final class Counts {
        private int submissionsWiped;
        private int reportsDeleted;
        private int pdfsDeleted;
        private int pdfDeleteFailures;
        private int snapshotsDeleted;
        private int snapshotsKept;
        private int descriptionsWiped;
        private int llmCacheDeleted;
        private int processedEventsDeleted;
        private int failedSteps;
        private int failedBatches;

        private RetentionResult toResult() {
            return new RetentionResult(submissionsWiped, reportsDeleted, pdfsDeleted, pdfDeleteFailures,
                    snapshotsDeleted, snapshotsKept, descriptionsWiped, llmCacheDeleted, processedEventsDeleted,
                    failedSteps, failedBatches);
        }
    }
}
