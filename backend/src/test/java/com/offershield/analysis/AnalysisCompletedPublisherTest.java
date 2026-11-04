package com.offershield.analysis;

import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.events.EventHeaders;
import com.offershield.common.events.Topics;
import com.offershield.common.logging.LogContext;
import com.offershield.support.LogCapture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-28: {@code analysis.completed} keyed by submission ID, deterministic event ID, request ID header, confirmed send. */
class AnalysisCompletedPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final AnalysisCompletedPublisher publisher = new AnalysisCompletedPublisher(kafka);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmed_sendsDeterministicIdAndHeaders() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        MDC.put(LogContext.REQUEST_ID, "req-p6");

        assertThat(publisher.publish(AnalysisFixtures.completion())).isTrue();

        ArgumentCaptor<ProducerRecord<String, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        ProducerRecord<String, Object> record = sent.getValue();
        UUID expectedId = UUID.nameUUIDFromBytes(("analysis.completed:" + AnalysisFixtures.SUBMISSION)
                .getBytes(StandardCharsets.UTF_8));
        assertThat(record.topic()).isEqualTo(Topics.ANALYSIS_COMPLETED);
        assertThat(record.key()).isEqualTo(AnalysisFixtures.SUBMISSION.toString());
        assertThat(record.value()).isInstanceOfSatisfying(AnalysisCompletedEvent.class, event -> {
            assertThat(event.eventId()).isEqualTo(expectedId);
            assertThat(event.eventType()).isEqualTo("analysis.completed");
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.requestId()).isEqualTo("req-p6");
            assertThat(event.occurredAt()).isEqualTo(AnalysisFixtures.completion().completedAt());
            assertThat(event.payload()).isEqualTo(AnalysisFixtures.completion().payload());
        });
        assertThat(header(record, EventHeaders.EVENT_ID)).isEqualTo(expectedId.toString());
        assertThat(header(record, EventHeaders.EVENT_TYPE)).isEqualTo("analysis.completed");
        assertThat(header(record, EventHeaders.REQUEST_ID)).isEqualTo("req-p6");
    }

    @Test
    @SuppressWarnings("unchecked")
    void brokerFailure_falseAndClassOnlyLogged() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("record value SECRET-EVIDENCE")));

        try (LogCapture logs = LogCapture.of(AnalysisCompletedPublisher.class)) {
            assertThat(publisher.publish(AnalysisFixtures.completion())).isFalse();
            assertThat(logs.events()).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .contains("IllegalStateException").doesNotContain("SECRET-EVIDENCE"));
        }
    }

    private static String header(ProducerRecord<String, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
