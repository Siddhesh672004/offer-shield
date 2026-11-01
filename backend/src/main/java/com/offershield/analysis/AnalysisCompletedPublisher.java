package com.offershield.analysis;

import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.events.EventHeaders;
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
 * Publishes {@code analysis.completed} (D-20, D-28), keyed by submission ID, with the deterministic event ID and the
 * request ID header, and waits for the broker's acknowledgement. Called only after the report's transaction has
 * committed. A failure is logged by exception class only and reported as {@code false}.
 */
@Component
public class AnalysisCompletedPublisher {

    static final Duration SEND_TIMEOUT = Duration.ofSeconds(20);
    private static final Logger log = LoggerFactory.getLogger(AnalysisCompletedPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AnalysisCompletedPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** @return {@code true} when the broker confirmed the write */
    public boolean publish(Completion completion) {
        String requestId = MDC.get(LogContext.REQUEST_ID);
        AnalysisCompletedEvent event = AnalysisCompletedEvent.of(completion.payload(), completion.completedAt(), requestId);
        ProducerRecord<String, Object> record = new ProducerRecord<>(Topics.ANALYSIS_COMPLETED,
                completion.payload().submissionId().toString(), event);
        header(record, EventHeaders.EVENT_ID, event.eventId().toString());
        header(record, EventHeaders.EVENT_TYPE, event.eventType());
        if (requestId != null) {
            header(record, EventHeaders.REQUEST_ID, requestId);
        }
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
        }
        return false;
    }

    private static void header(ProducerRecord<String, Object> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
