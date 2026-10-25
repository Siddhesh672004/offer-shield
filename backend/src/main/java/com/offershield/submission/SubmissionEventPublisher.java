package com.offershield.submission;

import com.offershield.common.events.EventHeaders;
import com.offershield.common.events.SubmissionCreatedEvent;
import com.offershield.common.events.Topics;
import com.offershield.common.logging.LogContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes {@code submission.created} (PRD §13.3, D-28) and waits a bounded time for the broker's acknowledgement.
 * Callers invoke it only after their own transaction has committed and outside any transaction (the upload and the
 * sweeper both do), so a rolled-back row is never announced and no database connection waits for Kafka. A failure is
 * logged and swallowed: the row is already QUEUED, so the sweeper re-publishes it with the same deterministic event
 * ID. The producer's own limits (max.block.ms, delivery.timeout.ms) are shorter than {@link #SEND_TIMEOUT}, so the send
 * normally completes or fails before this wait ends.
 */
@Component
public class SubmissionEventPublisher {

    static final Duration SEND_TIMEOUT = Duration.ofSeconds(20);
    private static final Logger log = LoggerFactory.getLogger(SubmissionEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SubmissionEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** @return {@code true} when the broker confirmed the write */
    public boolean publish(SubmissionQueued queued) {
        String requestId = MDC.get(LogContext.REQUEST_ID);
        SubmissionCreatedEvent event = SubmissionCreatedEvent.of(new SubmissionCreatedEvent.Payload(
                queued.submissionId(), queued.ownerUserId(), queued.collegeId(), queued.sourceType().name(),
                queued.contentSha256()), queued.createdAt(), requestId);
        ProducerRecord<String, Object> record = new ProducerRecord<>(Topics.SUBMISSION_CREATED,
                queued.submissionId().toString(), event);
        header(record, EventHeaders.EVENT_ID, event.eventId().toString());
        header(record, EventHeaders.EVENT_TYPE, event.eventType());
        if (requestId != null) {
            header(record, EventHeaders.REQUEST_ID, requestId);
        }

        String previousSubmissionId = MDC.get(LogContext.SUBMISSION_ID);
        MDC.put(LogContext.SUBMISSION_ID, queued.submissionId().toString());
        try {
            kafkaTemplate.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.info("event published topic={} eventId={}", record.topic(), event.eventId());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("event publish failed topic={} error=InterruptedException", record.topic());
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("event publish failed topic={} error={}", record.topic(), cause.getClass().getSimpleName());
        } catch (TimeoutException | RuntimeException ex) {
            // Exception messages may echo record contents; log the class only (D-27).
            log.warn("event publish failed topic={} error={}", record.topic(), ex.getClass().getSimpleName());
        } finally {
            if (previousSubmissionId == null) {
                MDC.remove(LogContext.SUBMISSION_ID);
            } else {
                MDC.put(LogContext.SUBMISSION_ID, previousSubmissionId);
            }
        }
        return false;
    }

    private static void header(ProducerRecord<String, Object> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
