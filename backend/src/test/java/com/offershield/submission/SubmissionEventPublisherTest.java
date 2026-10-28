package com.offershield.submission;

import com.offershield.common.events.EventHeaders;
import com.offershield.common.events.SubmissionCreatedEvent;
import com.offershield.common.events.Topics;
import com.offershield.common.logging.LogContext;
import com.offershield.support.LogCapture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-28 / PRD §13.3: {@code submission.created} carries a deterministic event ID, the request ID in a header, and no
 * letter text or contact details; a broker failure is logged by class only and never thrown. That it is sent only
 * after the commit and outside any transaction is pinned in {@code SubmissionServiceTest} ({@code rolledBack_noSend},
 * {@code committed_publishedAfterCommit_outsideAnyTransaction}) and {@code QueuedSubmissionSweeperTest}.
 */
class SubmissionEventPublisherTest {

    private static final UUID SUBMISSION = UUID.fromString("0190c9a0-0000-7000-8000-0000000000aa");
    private static final UUID OWNER = UUID.fromString("0190c9a0-0000-7000-8000-0000000000bb");
    private static final UUID COLLEGE = UUID.fromString("0190c9a0-0000-7000-8000-0000000000cc");
    private static final Instant CREATED = Instant.parse("2026-10-20T10:00:00Z");
    private static final String SHA = "a".repeat(64);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final SubmissionEventPublisher publisher = new SubmissionEventPublisher(kafka);

    @BeforeEach
    void stubSend() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        MDC.put(LogContext.REQUEST_ID, "req-123");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void committed_sendsDeterministicId() {
        assertThat(publisher.publish(queued())).isTrue();
        assertThat(publisher.publish(queued())).isTrue();

        ArgumentCaptor<ProducerRecord<String, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(sent.capture());
        ProducerRecord<String, Object> first = sent.getAllValues().get(0);
        ProducerRecord<String, Object> second = sent.getAllValues().get(1);

        UUID expectedId = UUID.nameUUIDFromBytes(("submission.created:" + SUBMISSION).getBytes(StandardCharsets.UTF_8));
        assertThat(first.topic()).isEqualTo(Topics.SUBMISSION_CREATED).isEqualTo("submission.created");
        assertThat(first.key()).isEqualTo(SUBMISSION.toString());
        assertThat(first.value()).isInstanceOfSatisfying(SubmissionCreatedEvent.class, event -> {
            assertThat(event.eventId()).isEqualTo(expectedId);
            assertThat(event.eventType()).isEqualTo("submission.created");
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.occurredAt()).isEqualTo(CREATED);
            assertThat(event.requestId()).isEqualTo("req-123");
            assertThat(event.payload()).isEqualTo(new SubmissionCreatedEvent.Payload(SUBMISSION, OWNER, COLLEGE, "PDF", SHA));
        });
        assertThat(header(first, EventHeaders.EVENT_ID)).isEqualTo(expectedId.toString());
        assertThat(header(first, EventHeaders.EVENT_TYPE)).isEqualTo("submission.created");
        assertThat(header(first, EventHeaders.REQUEST_ID)).isEqualTo("req-123");
        assertThat(((SubmissionCreatedEvent) second.value()).eventId()).as("re-publish keeps the ID").isEqualTo(expectedId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void noRequestId_noHeader() {
        MDC.clear();

        publisher.publish(queued());

        ArgumentCaptor<ProducerRecord<String, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().headers().lastHeader(EventHeaders.REQUEST_ID)).isNull();
    }

    @Test
    void sendFails_loggedByClassOnly_notThrown() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down x@example.com")));

        try (LogCapture logs = LogCapture.of(SubmissionEventPublisher.class)) {
            assertThat(publisher.publish(queued())).isFalse();

            assertThat(logs.events()).extracting(event -> event.getFormattedMessage())
                    .anySatisfy(line -> assertThat(line).contains("publish failed").contains("IllegalStateException"))
                    .allSatisfy(line -> assertThat(line).doesNotContain("example.com"));
        }
    }

    @Test
    void sendThrowsSynchronously_notThrown() {
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("metadata timeout"));

        assertThat(publisher.publish(queued())).isFalse();
    }

    @Test
    void submissionIdInLogContextOnlyWhilePublishing() {
        MDC.put(LogContext.SUBMISSION_ID, "outer");

        publisher.publish(queued());

        assertThat(MDC.get(LogContext.SUBMISSION_ID)).isEqualTo("outer");
    }

    private static SubmissionQueued queued() {
        return new SubmissionQueued(SUBMISSION, OWNER, COLLEGE, SourceType.PDF, SHA, CREATED);
    }

    private static String header(ProducerRecord<String, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
