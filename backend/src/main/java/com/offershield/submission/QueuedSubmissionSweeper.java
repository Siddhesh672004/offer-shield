package com.offershield.submission;

import com.offershield.common.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fixes the "saved but never published" gap of after-commit publishing (PRD §13.3, D-28). Every minute it looks at
 * QUEUED submissions: one whose last publish is older than 2, 4, 8, 16 minutes (doubling per re-publish) is published
 * again with the same event ID, which the analysis consumer deduplicates; after 4 re-publishes, and a final 16-minute
 * wait, it becomes FAILED (about 46 minutes after upload). A re-publish counts only once the broker confirmed it
 * (P13): the row is then updated with a compare-and-set in its own short transaction. If Kafka refuses a publish,
 * nothing is counted and the rest waits for the next run, so a long Kafka outage delays rows but never fails them.
 */
@Component
public class QueuedSubmissionSweeper {

    static final int MAX_REPUBLISHES = 4;
    static final int BATCH_SIZE = 100;
    private static final Logger log = LoggerFactory.getLogger(QueuedSubmissionSweeper.class);

    private final SubmissionRepository submissions;
    private final SubmissionEventPublisher publisher;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public QueuedSubmissionSweeper(SubmissionRepository submissions, SubmissionEventPublisher publisher,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${offershield.submission.sweeper.enabled:true}") boolean enabled) {
        this.submissions = submissions;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
    }

    /** How long after its last publish a row with {@code publishAttempts} re-publishes is next looked at. */
    static Duration waitAfter(int publishAttempts) {
        int exponent = Math.min(publishAttempts, MAX_REPUBLISHES - 1) + 1;
        return Duration.ofMinutes(1L << exponent);
    }

    @Scheduled(fixedDelayString = "${offershield.submission.sweeper.interval:60s}",
            initialDelayString = "${offershield.submission.sweeper.interval:60s}")
    public void run() {
        if (!enabled) {
            return;
        }
        MDC.put(LogContext.REQUEST_ID, UUID.randomUUID().toString());
        try {
            SweepResult result = sweep(Instant.now());
            if (result.republished() > 0 || result.failed() > 0) {
                log.info("sweep republished={} failed={}", result.republished(), result.failed());
            }
        } catch (RuntimeException ex) {
            log.warn("sweep failed: {}", ex.getClass().getSimpleName());
        } finally {
            MDC.remove(LogContext.REQUEST_ID);
        }
    }

    public SweepResult sweep(Instant now) {
        List<SweepCandidate> candidates = submissions.findSweepCandidates(now.minus(waitAfter(0)), Limit.of(BATCH_SIZE));
        int republished = 0;
        int failed = 0;
        for (SweepCandidate candidate : candidates) {
            if (candidate.lastPublishedAt().plus(waitAfter(candidate.publishAttempts())).isAfter(now)) {
                continue;
            }
            MDC.put(LogContext.SUBMISSION_ID, candidate.id().toString());
            try {
                if (candidate.publishAttempts() >= MAX_REPUBLISHES) {
                    if (markFailed(candidate)) {
                        failed++;
                        log.warn("submission failed: not analysed after {} re-publishes", MAX_REPUBLISHES);
                    }
                    continue;
                }
                if (!publisher.publish(candidate.toQueued())) {
                    // Not counted (P13): an outage longer than the schedule never turns undelivered rows FAILED.
                    log.warn("sweep paused: publish refused, attempt not counted; the next run tries again");
                    break;
                }
                if (recordRepublish(candidate, now)) {
                    republished++;
                } // else analysed or swept meanwhile: the consumer recognises the repeated event ID

            } finally {
                MDC.remove(LogContext.SUBMISSION_ID);
            }
        }
        return new SweepResult(republished, failed);
    }

    private boolean recordRepublish(SweepCandidate candidate, Instant now) {
        Integer updated = transaction.execute(status ->
                submissions.recordRepublish(candidate.id(), candidate.publishAttempts(), now));
        return updated != null && updated == 1;
    }

    private boolean markFailed(SweepCandidate candidate) {
        Integer updated = transaction.execute(status ->
                submissions.markFailed(candidate.id(), candidate.publishAttempts()));
        return updated != null && updated == 1;
    }

    /** What one sweep did. */
    public record SweepResult(int republished, int failed) {
    }
}
