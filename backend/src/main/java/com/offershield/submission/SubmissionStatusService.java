package com.offershield.submission;

import com.offershield.common.kafka.ProcessedEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Applies {@code analysis.completed} to a submission (02 §7): QUEUED → READY or PARTIAL. A completion that arrives
 * after the sweeper gave up (FAILED, e.g. after a long broker outage) also moves it, because the report exists and is
 * worth showing; READY, PARTIAL and DELETED rows are never changed. Dedupe by {@code processed_event} in the same
 * transaction (D-28).
 */
@Service
public class SubmissionStatusService {

    /** The consumer group of {@code analysis.completed} that updates statuses, also its {@code processed_event} name. */
    public static final String CONSUMER = "submission-status";
    private static final Logger log = LoggerFactory.getLogger(SubmissionStatusService.class);

    private final SubmissionRepository submissions;
    private final ProcessedEvents processedEvents;

    public SubmissionStatusService(SubmissionRepository submissions, ProcessedEvents processedEvents) {
        this.submissions = submissions;
        this.processedEvents = processedEvents;
    }

    /** @return {@code true} when the status changed */
    @Transactional
    public boolean applyAnalysisOutcome(UUID submissionId, UUID eventId, SubmissionStatus outcome) {
        if (outcome != SubmissionStatus.READY && outcome != SubmissionStatus.PARTIAL) {
            throw new IllegalArgumentException("an analysis outcome is READY or PARTIAL");
        }
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            log.info("status update skipped: duplicate event");
            return false;
        }
        boolean changed = submissions.markAnalysed(submissionId, outcome) == 1;
        log.info(changed ? "status updated to {}" : "status unchanged ({}): submission not waiting for analysis", outcome);
        return changed;
    }
}
