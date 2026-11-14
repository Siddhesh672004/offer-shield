package com.offershield.registry;

import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.common.Ids;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.kafka.InvalidEventException;
import com.offershield.common.kafka.KafkaConsumerConfig;
import com.offershield.support.IntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P9-01 (D-04, D-20, D-28): the {@code registry-snapshot} consumer of {@code analysis.completed}. A completion is stored
 * once per event (dedupe by {@code processed_event}), upserted by submission ID, with the sender's registrable domain
 * (only one that passes the SSRF guard), the HMAC keys, the SimHash and the signal summary. Bad payloads go to the DLT
 * without retries; a database failure is retried. The listener method is called directly (no broker) except for the
 * error-handler checks, which use the application's real error handler.
 */
class ReportSnapshotListenerTest extends IntegrationTest {

    @Autowired
    private ReportSnapshotListener listener;
    @Autowired
    private ReportSnapshotService snapshots;
    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private JdbcTemplate jdbc;

    private RegistryTestData data;
    private UUID owner;
    private UUID submission;

    @BeforeEach
    void setUp() throws Exception {
        data = new RegistryTestData(provisioning, mockMvc, snapshots);
        owner = data.user();
        submission = data.submission(owner);
    }

    @Test
    void upsert_idempotent() {
        String phone = RegistryTestData.key();
        String upi = RegistryTestData.key();
        long simhash = RegistryTestData.simhash();
        Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AnalysisCompletedEvent first = AnalysisCompletedEvent.of(RegistryTestData.payload(submission, owner,
                "hr.mail.young-firm-p9.com", List.of(phone), List.of(upi), simhash), completedAt, "req-p9");

        listener.onAnalysisCompleted(first);
        listener.onAnalysisCompleted(first); // redelivery

        ReportSnapshotView stored = snapshots.find(submission).orElseThrow();
        assertThat(stored.ownerUserId()).isEqualTo(owner);
        assertThat(stored.band()).isEqualTo("HIGH");
        assertThat(stored.score()).isEqualTo(30);
        assertThat(stored.hardOverride()).isTrue();
        assertThat(stored.partial()).isFalse();
        assertThat(stored.domain()).as("registrable domain").isEqualTo("young-firm-p9.com");
        assertThat(stored.phoneHmacs()).containsExactly(phone);
        assertThat(stored.upiHmacs()).containsExactly(upi);
        assertThat(stored.simhash()).isEqualTo(simhash);
        assertThat(stored.indicatorsComplete()).isTrue();
        assertThat(stored.completedAt()).isEqualTo(completedAt);
        assertThat(stored.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.code()).isEqualTo("C1");
            assertThat(signal.evidence()).isEqualTo("pay Rs. 5,000 refundable deposit");
            assertThat(signal.sourceKind()).isEqualTo(1);
        });
        assertThat(rows("SELECT COUNT(*) FROM report_snapshot WHERE submission_id = ?")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = 'registry-snapshot' "
                + "AND event_id = ?", Integer.class, first.eventId().toString())).isEqualTo(1);

        // A different completion of the same submission (another event ID) overwrites the copy: upsert, not a 2nd row.
        AnalysisCompletedEvent other = new AnalysisCompletedEvent(UUID.randomUUID(), first.eventType(), 1,
                completedAt, "req-p9", new AnalysisCompletedEvent.Payload(submission, owner, null,
                AnalysisCompletedEvent.PARTIAL, 10, "LOW", false, List.of("C5"), List.of(), null, List.of(), List.of(),
                null, false));
        listener.onAnalysisCompleted(other);

        ReportSnapshotView updated = snapshots.find(submission).orElseThrow();
        assertThat(updated.band()).isEqualTo("LOW");
        assertThat(updated.partial()).isTrue();
        assertThat(updated.domain()).isNull();
        assertThat(updated.phoneHmacs()).isEmpty();
        assertThat(updated.simhash()).isNull();
        assertThat(updated.indicatorsComplete()).isFalse();
        assertThat(rows("SELECT COUNT(*) FROM report_snapshot WHERE submission_id = ?")).isEqualTo(1);
    }

    @Test
    void preP9Event_noKeys_storedIncomplete() {
        AnalysisCompletedEvent old = AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(submission, owner,
                null, AnalysisCompletedEvent.READY, 0, "LOW", false, List.of(), List.of(), "young-firm-p9.com", null,
                null, null, false), Instant.now(), null);

        listener.onAnalysisCompleted(old);

        ReportSnapshotView stored = snapshots.find(submission).orElseThrow();
        assertThat(stored.phoneHmacs()).isEmpty();
        assertThat(stored.upiHmacs()).isEmpty();
        assertThat(stored.indicatorsComplete()).isFalse();
    }

    @Test
    void ipLiteralSender_noDomainKept() {
        listener.onAnalysisCompleted(AnalysisCompletedEvent.of(RegistryTestData.payload(submission, owner, "127.0.0.1",
                List.of(), List.of(), null), Instant.now(), null));

        assertThat(snapshots.find(submission).orElseThrow().domain()).isNull();
    }

    @Test
    void findForOwner_otherUser_empty() {
        listener.onAnalysisCompleted(AnalysisCompletedEvent.of(RegistryTestData.payload(submission, owner, null,
                List.of(), List.of(), null), Instant.now(), null));

        assertThat(snapshots.findForOwner(submission, owner)).isPresent();
        assertThat(snapshots.findForOwner(submission, UUID.randomUUID())).isEmpty();
        assertThat(snapshots.findForOwner(UUID.randomUUID(), owner)).isEmpty();
    }

    @Test
    void invalidEvents_rejectedNothingWritten() {
        AnalysisCompletedEvent valid = AnalysisCompletedEvent.of(RegistryTestData.payload(submission, owner, null,
                List.of(), List.of(), null), Instant.now(), null);
        AnalysisCompletedEvent.Payload p = valid.payload();
        List<AnalysisCompletedEvent> bad = List.of(
                new AnalysisCompletedEvent(null, valid.eventType(), 1, valid.occurredAt(), null, p),
                new AnalysisCompletedEvent(valid.eventId(), valid.eventType(), 1, valid.occurredAt(), null, null),
                AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(submission, null, null, "READY", 0, "LOW",
                        false, List.of(), List.of(), null, List.of(), List.of(), null, true), Instant.now(), null),
                AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(submission, owner, null, "DONE", 0, "LOW",
                        false, List.of(), List.of(), null, List.of(), List.of(), null, true), Instant.now(), null),
                AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(submission, owner, null, "READY", 0, "SAFE",
                        false, List.of(), List.of(), null, List.of(), List.of(), null, true), Instant.now(), null),
                AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(submission, owner, null, "READY", 101,
                        "HIGH", false, List.of(), List.of(), null, List.of(), List.of(), null, true), Instant.now(), null),
                // A raw phone number where an HMAC key belongs is refused: the registry never stores raw values.
                AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(submission, owner, null, "READY", 0, "LOW",
                        false, List.of(), List.of(), null, List.of("+919999901234"), List.of(), null, true),
                        Instant.now(), null));

        for (AnalysisCompletedEvent event : bad) {
            assertThatThrownBy(() -> listener.onAnalysisCompleted(event)).isInstanceOf(InvalidEventException.class);
        }
        assertThatThrownBy(() -> listener.onAnalysisCompleted(null)).isInstanceOf(InvalidEventException.class);
        assertThat(snapshots.find(submission)).isEmpty();
    }

    @Test
    void p13Fields_storedAndRead() {
        // S2 / N2: the sender source, the letter's email-domain count and the masked hints reach the registry's copy.
        String phone = RegistryTestData.key();
        String upi = RegistryTestData.key();
        List<AnalysisCompletedEvent.MarkerHint> hints = List.of(
                new AnalysisCompletedEvent.MarkerHint("PHONE", phone, "TYPED", "••••1234"),
                new AnalysisCompletedEvent.MarkerHint("UPI", upi, "LETTER", "••••ar@okaxis"));

        listener.onAnalysisCompleted(AnalysisCompletedEvent.of(p13Payload(List.of(phone), List.of(upi), "LETTER", 2,
                hints), Instant.now(), null));

        ReportSnapshotView stored = snapshots.find(submission).orElseThrow();
        assertThat(stored.senderSource()).isEqualTo("LETTER");
        assertThat(stored.letterEmailDomains()).isEqualTo(2);
        assertThat(stored.markerHints()).containsExactlyElementsOf(hints);
    }

    @Test
    void invalidP13Fields_rejected() {
        String phone = RegistryTestData.key();
        List<AnalysisCompletedEvent.Payload> bad = List.of(
                p13Payload(List.of(phone), List.of(), "SOMEWHERE", 1, List.of()),
                p13Payload(List.of(phone), List.of(), "TYPED", -1, List.of()),
                // A hint that is not masked (a raw number) is refused: the registry never stores raw values.
                p13Payload(List.of(phone), List.of(), "TYPED", 1, List.of(
                        new AnalysisCompletedEvent.MarkerHint("PHONE", phone, "TYPED", "+91 99999 01234"))),
                p13Payload(List.of(phone), List.of(), "TYPED", 1, List.of(
                        new AnalysisCompletedEvent.MarkerHint("PHONE", "+919999901234", "TYPED", "••••1234"))),
                p13Payload(List.of(phone), List.of(), "TYPED", 1, List.of(
                        new AnalysisCompletedEvent.MarkerHint("EMAIL", phone, "TYPED", "••••1234"))),
                p13Payload(List.of(phone), List.of(), "TYPED", 1, List.of(
                        new AnalysisCompletedEvent.MarkerHint("PHONE", phone, "AI", "••••1234"))));

        for (AnalysisCompletedEvent.Payload payload : bad) {
            AnalysisCompletedEvent event = AnalysisCompletedEvent.of(payload, Instant.now(), null);
            assertThatThrownBy(() -> listener.onAnalysisCompleted(event)).isInstanceOf(InvalidEventException.class);
        }
        assertThat(snapshots.find(submission)).isEmpty();
    }

    private AnalysisCompletedEvent.Payload p13Payload(List<String> phones, List<String> upis, String senderSource,
                                                      Integer letterEmailDomains,
                                                      List<AnalysisCompletedEvent.MarkerHint> hints) {
        return new AnalysisCompletedEvent.Payload(submission, owner, null, AnalysisCompletedEvent.READY, 30, "HIGH",
                true, List.of(), List.of(), "young-firm-p9.com", phones, upis, null, true, senderSource,
                letterEmailDomains, hints);
    }

    @Test
    void poison_toDlt() {
        ConsumerAwareRecordRecoverer deadLetter = mock(ConsumerAwareRecordRecoverer.class);
        DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(deadLetter, new SimpleMeterRegistry());
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, Object> malformed = new ConsumerRecord<>("analysis.completed", 1, 42L, "key", null);
        ConsumerRecord<String, Object> invalid = new ConsumerRecord<>("analysis.completed", 2, 7L, "key", "value");

        handler.handleRemaining(new DeserializationException("failed to deserialize",
                "{not json".getBytes(StandardCharsets.UTF_8), false, new IllegalArgumentException()),
                List.of(malformed), consumer, mock(MessageListenerContainer.class));
        handler.handleRemaining(new ListenerExecutionFailedException("listener failed",
                new InvalidEventException("analysis.completed without IDs")), List.of(invalid), consumer,
                mock(MessageListenerContainer.class));

        verify(deadLetter, times(1)).accept(eq(malformed), any(), any());
        verify(deadLetter, times(1)).accept(eq(invalid), any(), any());
        verify(consumer, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void databaseFailure_retriedNotDeadLettered() {
        ConsumerAwareRecordRecoverer deadLetter = mock(ConsumerAwareRecordRecoverer.class);
        DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(deadLetter, new SimpleMeterRegistry());
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("analysis.completed", 0, 9L, "key", "value");

        assertThatThrownBy(() -> handler.handleRemaining(new ListenerExecutionFailedException("listener failed",
                new DataAccessResourceFailureException("database down")), List.of(record), consumer,
                mock(MessageListenerContainer.class))).hasMessage("Record in retry and not yet recovered");

        verify(deadLetter, never()).accept(any(), any(), any());
        verify(consumer).seek(new TopicPartition("analysis.completed", 0), 9L);
    }

    private int rows(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class, (Object) Ids.toBytes(submission));
        return count == null ? 0 : count;
    }
}
