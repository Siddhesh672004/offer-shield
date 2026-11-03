package com.offershield.analysis;

import com.offershield.common.events.SubmissionCreatedEvent;
import com.offershield.common.events.Topics;
import com.offershield.common.kafka.InvalidEventException;
import com.offershield.common.logging.LogContext;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code analysis} of {@code submission.created} (D-20). One record per poll, because an analysis
 * waits on lookups (P6-01). The value is read as {@link SubmissionCreatedEvent} whatever its type header says; a
 * record that is not valid JSON, or has no IDs, goes to {@code submission.created.DLT} without retries
 * ({@code KafkaConsumerConfig}). Request and submission IDs reach the MDC through {@code KafkaLogContextInterceptor}.
 */
@Component
public class SubmissionCreatedListener {

    public static final String ID = "analysis";

    private final AnalysisService analysis;

    public SubmissionCreatedListener(AnalysisService analysis) {
        this.analysis = analysis;
    }

    @KafkaListener(id = ID, groupId = ID, topics = Topics.SUBMISSION_CREATED, properties = {
            "max.poll.records=1",
            "spring.json.value.default.type=com.offershield.common.events.SubmissionCreatedEvent"})
    public void onSubmissionCreated(SubmissionCreatedEvent event) {
        if (event == null || event.eventId() == null || event.payload() == null
                || event.payload().submissionId() == null) {
            throw new InvalidEventException("submission.created without event or submission ID");
        }
        if (event.payload().ownerUserId() != null) {
            MDC.put(LogContext.USER_ID, event.payload().ownerUserId().toString());
        }
        analysis.analyse(event);
    }
}
