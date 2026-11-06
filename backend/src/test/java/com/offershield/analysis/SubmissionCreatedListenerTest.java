package com.offershield.analysis;

import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckOutcome;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.lookup.DnsClient;
import com.offershield.analysis.lookup.DomainValidator;
import com.offershield.analysis.lookup.RdapClient;
import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.ScoringEngine;
import com.offershield.analysis.checks.Letters;
import com.offershield.common.events.SubmissionCreatedEvent;
import com.offershield.common.kafka.InvalidEventException;
import com.offershield.common.kafka.KafkaConsumerConfig;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.submission.SubmissionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P6-01 / P6-07, D-28: the {@code analysis} consumer of {@code submission.created}. A first delivery analyses and
 * saves; a duplicate is skipped, but re-sends {@code analysis.completed} when the report already exists (review focus
 * #4: a crash after the save must not leave the submission stuck); bad payloads go to the DLT without retries.
 */
class SubmissionCreatedListenerTest {

    private final SubmissionService submissions = mock(SubmissionService.class);
    private final ReportService reports = mock(ReportService.class);
    private final ProcessedEvents processedEvents = mock(ProcessedEvents.class);
    private final AnalysisCompletedPublisher publisher = mock(AnalysisCompletedPublisher.class);
    private final RdapClient rdap = mock(RdapClient.class);
    private final DnsClient dns = mock(DnsClient.class);
    private final AnalysisService analysis = new AnalysisService(submissions, new DomainValidator(),
            AnalysisFixtures.checks(rdap, dns), new ScoringEngine(), reports, processedEvents, publisher,
            AnalysisFixtures.aiFindingNothing(), new ExtractionMerger(), AnalysisFixtures.registryFindingNothing());
    private final SubmissionCreatedListener listener = new SubmissionCreatedListener(analysis);
    private final SubmissionCreatedEvent event = AnalysisFixtures.event();

    @Test
    void firstDelivery_analyses() {
        when(submissions.findContent(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(
                AnalysisFixtures.content(Letters.fixture(Letters.DEPOSIT_SCAM), null, null)));
        when(reports.save(eq(event.eventId()), any())).thenReturn(true);
        when(reports.findCompletion(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(AnalysisFixtures.completion()));
        when(publisher.publish(any())).thenReturn(true);

        listener.onSubmissionCreated(event);

        ArgumentCaptor<NewReport> saved = ArgumentCaptor.forClass(NewReport.class);
        verify(reports).save(eq(event.eventId()), saved.capture());
        NewReport report = saved.getValue();
        assertThat(report.submissionId()).isEqualTo(AnalysisFixtures.SUBMISSION);
        assertThat(report.ownerUserId()).isEqualTo(AnalysisFixtures.OWNER);
        assertThat(report.score().band()).isEqualTo(Band.HIGH);
        assertThat(report.results()).extracting(CheckResult::code)
                .containsExactly(CheckCode.C1, CheckCode.C2, CheckCode.C5, CheckCode.C6, CheckCode.C8, CheckCode.C9);
        verify(publisher).publish(AnalysisFixtures.completion());
    }

    @Test
    void duplicate_skipped() {
        when(processedEvents.isProcessed(ReportService.CONSUMER, event.eventId())).thenReturn(true);
        when(reports.findCompletion(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.empty());

        listener.onSubmissionCreated(event);

        verifyNoInteractions(submissions, publisher, rdap, dns);
        verify(reports, never()).save(any(), any());
    }

    @Test
    void duplicateWithExistingReport_resendsCompletion() {
        when(processedEvents.isProcessed(ReportService.CONSUMER, event.eventId())).thenReturn(true);
        when(reports.findCompletion(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(AnalysisFixtures.completion()));
        when(publisher.publish(any())).thenReturn(true);

        listener.onSubmissionCreated(event);

        verify(publisher, times(1)).publish(AnalysisFixtures.completion());
        verifyNoInteractions(submissions, rdap, dns);
        verify(reports, never()).save(any(), any());
    }

    @Test
    void lostRaceToADuplicate_resendsStoredCompletion() {
        when(submissions.findContent(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(
                AnalysisFixtures.content("Dear Candidate, welcome.", null, null)));
        when(reports.save(eq(event.eventId()), any())).thenReturn(false);
        when(reports.findCompletion(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(AnalysisFixtures.completion()));
        when(publisher.publish(any())).thenReturn(true);

        listener.onSubmissionCreated(event);

        verify(publisher).publish(AnalysisFixtures.completion());
    }

    @Test
    void submissionGone_markedProcessedWithoutReport() {
        when(submissions.findContent(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.empty());

        listener.onSubmissionCreated(event);

        verify(reports).markSkipped(event.eventId());
        verify(reports, never()).save(any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void completionNotConfirmed_throwsSoTheRecordIsRetried() {
        when(submissions.findContent(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(
                AnalysisFixtures.content("Dear Candidate, welcome.", null, null)));
        when(reports.save(eq(event.eventId()), any())).thenReturn(true);
        when(reports.findCompletion(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(AnalysisFixtures.completion()));
        when(publisher.publish(any())).thenReturn(false);

        assertThatThrownBy(() -> listener.onSubmissionCreated(event)).isInstanceOf(CompletionNotSentException.class);
        verify(reports).save(eq(event.eventId()), any()); // the report is kept; the redelivery re-sends it
    }

    @Test
    void eventWithoutIds_invalid() {
        SubmissionCreatedEvent noPayload = new SubmissionCreatedEvent(event.eventId(), event.eventType(), 1,
                event.occurredAt(), null, null);
        SubmissionCreatedEvent noEventId = new SubmissionCreatedEvent(null, event.eventType(), 1, event.occurredAt(),
                null, event.payload());

        assertThatThrownBy(() -> listener.onSubmissionCreated(noPayload)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> listener.onSubmissionCreated(noEventId)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> listener.onSubmissionCreated(null)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(submissions, reports, publisher);
    }

    @Test
    void poison_toDltNoRetry() {
        ConsumerAwareRecordRecoverer deadLetter = mock(ConsumerAwareRecordRecoverer.class);
        DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(deadLetter, new SimpleMeterRegistry());
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("submission.created", 1, 42L, "key", null);
        DeserializationException malformed = new DeserializationException("failed to deserialize",
                "{not json".getBytes(StandardCharsets.UTF_8), false, new IllegalArgumentException());

        handler.handleRemaining(malformed, List.of(record), consumer, mock(MessageListenerContainer.class));

        verify(deadLetter, times(1)).accept(eq(record), any(), any());
        verify(consumer, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void invalidPayload_toDltNoRetry() {
        ConsumerAwareRecordRecoverer deadLetter = mock(ConsumerAwareRecordRecoverer.class);
        DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(deadLetter, new SimpleMeterRegistry());
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("submission.created", 0, 7L, "key", "value");

        handler.handleRemaining(new ListenerExecutionFailedException("listener failed",
                new InvalidEventException("submission.created without IDs")), List.of(record), consumer,
                mock(MessageListenerContainer.class));

        verify(deadLetter, times(1)).accept(eq(record), any(), any());
        verify(consumer, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void transientFailure_retriedNotDeadLettered() {
        ConsumerAwareRecordRecoverer deadLetter = mock(ConsumerAwareRecordRecoverer.class);
        DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(deadLetter, new SimpleMeterRegistry());
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("submission.created", 2, 9L, "key", "value");

        // The handler seeks back and tells the container (by this exception) that the record will come again.
        assertThatThrownBy(() -> handler.handleRemaining(new ListenerExecutionFailedException("listener failed",
                new CompletionNotSentException()), List.of(record), consumer, mock(MessageListenerContainer.class)))
                .hasMessage("Record in retry and not yet recovered");

        verify(deadLetter, never()).accept(any(), any(), any());
        verify(consumer).seek(new TopicPartition("submission.created", 2), 9L);
    }

    @Test
    void transientFailure_threeRetriesThenDeadLettered() {
        // P13 (test gap): D-28 "3 retries → <topic>.DLT". The listener container is a mock that is not running, so
        // each back-off sleep (1, 2, 4 s in production) ends at once.
        ConsumerAwareRecordRecoverer deadLetter = mock(ConsumerAwareRecordRecoverer.class);
        DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(deadLetter, new SimpleMeterRegistry());
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("submission.created", 1, 11L, "key", "value");
        ListenerExecutionFailedException failure = new ListenerExecutionFailedException("listener failed",
                new CompletionNotSentException());

        long start = System.nanoTime();
        for (int delivery = 1; delivery <= 3; delivery++) {
            assertThatThrownBy(() -> handler.handleRemaining(failure, List.of(record), consumer, container))
                    .as("delivery %d is retried", delivery)
                    .hasMessage("Record in retry and not yet recovered");
            verify(deadLetter, never()).accept(any(), any(), any());
        }
        handler.handleRemaining(failure, List.of(record), consumer, container);

        verify(consumer, times(3)).seek(new TopicPartition("submission.created", 1), 11L);
        verify(deadLetter, times(1)).accept(eq(record), any(), any());
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
    }

    @Test
    void deadLettered_countedPerTopic_withoutRecordData() {
        // P13 (silent failure 1): a Micrometer counter of dead-lettered records, tagged with the topic only.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultErrorHandler handler = KafkaConsumerConfig.errorHandler(mock(ConsumerAwareRecordRecoverer.class),
                registry);
        Consumer<?, ?> consumer = mock(Consumer.class);
        for (long offset = 1; offset <= 2; offset++) {
            ConsumerRecord<String, Object> record = new ConsumerRecord<>("submission.created", 0, offset,
                    "submission-key", "value");
            handler.handleRemaining(new ListenerExecutionFailedException("listener failed",
                    new InvalidEventException("submission.created without IDs")), List.of(record), consumer,
                    mock(MessageListenerContainer.class));
        }

        assertThat(registry.find("offershield.kafka.dead.letters").tag("topic", "submission.created").counter())
                .isNotNull()
                .satisfies(counter -> assertThat(counter.count()).isEqualTo(2.0));
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey).containsOnly("topic"));
    }

    @Test
    void unavailableLookup_reportPartial() {
        when(submissions.findContent(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(
                AnalysisFixtures.content("Dear Candidate, welcome aboard.", "hr@young-firm.com", "young-firm.com")));
        when(rdap.lookup(any())).thenReturn(com.offershield.analysis.lookup.RdapLookup.unavailable());
        when(dns.mailRecords(any())).thenReturn(com.offershield.analysis.lookup.MailRecords.found(true, true));
        when(reports.save(eq(event.eventId()), any())).thenReturn(true);
        when(reports.findCompletion(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(AnalysisFixtures.completion()));
        when(publisher.publish(any())).thenReturn(true);

        listener.onSubmissionCreated(event);

        ArgumentCaptor<NewReport> saved = ArgumentCaptor.forClass(NewReport.class);
        verify(reports).save(eq(event.eventId()), saved.capture());
        assertThat(saved.getValue().partial()).isTrue();
        assertThat(saved.getValue().results()).filteredOn(result -> result.code() == CheckCode.C5)
                .singleElement().extracting(CheckResult::outcome).isEqualTo(CheckOutcome.UNAVAILABLE);
        assertThat(saved.getValue().senderDomain()).isEqualTo("young-firm.com");
    }
}
