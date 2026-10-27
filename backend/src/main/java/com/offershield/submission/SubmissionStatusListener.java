package com.offershield.submission;

import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.events.Topics;
import com.offershield.common.kafka.InvalidEventException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code submission-status} of {@code analysis.completed} (D-20 fan-out next to the registry's
 * {@code registry-snapshot} group, P9): moves the submission to READY or PARTIAL. A record that is not valid JSON,
 * has no IDs or an unknown outcome goes to {@code analysis.completed.DLT} without retries ({@code KafkaConsumerConfig}).
 */
@Component
public class SubmissionStatusListener {

    public static final String ID = "submission-status";

    private final SubmissionStatusService statusService;

    public SubmissionStatusListener(SubmissionStatusService statusService) {
        this.statusService = statusService;
    }

    @KafkaListener(id = ID, groupId = ID, topics = Topics.ANALYSIS_COMPLETED,
            properties = "spring.json.value.default.type=com.offershield.common.events.AnalysisCompletedEvent")
    public void onAnalysisCompleted(AnalysisCompletedEvent event) {
        if (event == null || event.eventId() == null || event.payload() == null
                || event.payload().submissionId() == null) {
            throw new InvalidEventException("analysis.completed without event or submission ID");
        }
        SubmissionStatus outcome = switch (String.valueOf(event.payload().outcome())) {
            case AnalysisCompletedEvent.READY -> SubmissionStatus.READY;
            case AnalysisCompletedEvent.PARTIAL -> SubmissionStatus.PARTIAL;
            default -> throw new InvalidEventException("analysis.completed with an unknown outcome");
        };
        statusService.applyAnalysisOutcome(event.payload().submissionId(), event.eventId(), outcome);
    }
}
