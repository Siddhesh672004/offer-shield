package com.offershield.submission;

import com.offershield.support.NoOpTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** D-28 sweeper: re-publish QUEUED rows after 2/4/8/16 minutes; after 4 re-publishes the row becomes FAILED. */
class QueuedSubmissionSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final SubmissionEventPublisher publisher = mock(SubmissionEventPublisher.class);
    private final QueuedSubmissionSweeper sweeper = new QueuedSubmissionSweeper(submissions, publisher,
            new NoOpTransactionManager(), true);

    @BeforeEach
    void defaults() {
        when(submissions.recordRepublish(any(), anyInt(), any())).thenReturn(1);
        when(submissions.markFailed(any(), anyInt())).thenReturn(1);
        when(publisher.publish(any())).thenReturn(true);
    }

    @Test
    void stale_republished() {
        SweepCandidate stale = candidate(0, NOW.minus(Duration.ofMinutes(3)));
        candidates(stale);

        QueuedSubmissionSweeper.SweepResult result = sweeper.sweep(NOW);

        verify(submissions).findSweepCandidates(NOW.minus(Duration.ofMinutes(2)), Limit.of(QueuedSubmissionSweeper.BATCH_SIZE));
        verify(submissions).recordRepublish(stale.id(), 0, NOW);
        verify(publisher).publish(stale.toQueued());
        assertThat(result).isEqualTo(new QueuedSubmissionSweeper.SweepResult(1, 0));
    }

    @Test
    void fresh_skipped() {
        // One re-publish done 3 minutes ago: the next one waits 4 minutes.
        candidates(candidate(1, NOW.minus(Duration.ofMinutes(3))));

        assertThat(sweeper.sweep(NOW)).isEqualTo(new QueuedSubmissionSweeper.SweepResult(0, 0));

        verify(submissions, never()).recordRepublish(any(), anyInt(), any());
        verify(submissions, never()).markFailed(any(), anyInt());
        verifyNoInteractions(publisher);
    }

    @Test
    void backoffDoubles() {
        assertThat(QueuedSubmissionSweeper.waitAfter(0)).isEqualTo(Duration.ofMinutes(2));
        assertThat(QueuedSubmissionSweeper.waitAfter(1)).isEqualTo(Duration.ofMinutes(4));
        assertThat(QueuedSubmissionSweeper.waitAfter(2)).isEqualTo(Duration.ofMinutes(8));
        assertThat(QueuedSubmissionSweeper.waitAfter(3)).isEqualTo(Duration.ofMinutes(16));
        assertThat(QueuedSubmissionSweeper.waitAfter(4)).as("after the last re-publish").isEqualTo(Duration.ofMinutes(16));

        SweepCandidate notYet = candidate(2, NOW.minus(Duration.ofMinutes(8)).plusSeconds(1));
        SweepCandidate due = candidate(2, NOW.minus(Duration.ofMinutes(8)));
        candidates(notYet, due);

        sweeper.sweep(NOW);

        verify(submissions, never()).recordRepublish(eq(notYet.id()), anyInt(), any());
        verify(submissions).recordRepublish(due.id(), 2, NOW);
    }

    @Test
    void timeline_republishesAt2_6_14_30_thenFailsAt46Minutes() {
        Instant created = NOW;
        assertThat(created.plus(QueuedSubmissionSweeper.waitAfter(0))).isEqualTo(created.plus(Duration.ofMinutes(2)));
        Instant t = created;
        int attempts = 0;
        StringBuilder republishedAt = new StringBuilder();
        while (attempts < QueuedSubmissionSweeper.MAX_REPUBLISHES) {
            t = t.plus(QueuedSubmissionSweeper.waitAfter(attempts));
            attempts++;
            republishedAt.append(Duration.between(created, t).toMinutes()).append(' ');
        }
        Instant failedAt = t.plus(QueuedSubmissionSweeper.waitAfter(attempts));

        assertThat(republishedAt.toString().trim()).isEqualTo("2 6 14 30");
        assertThat(Duration.between(created, failedAt)).isEqualTo(Duration.ofMinutes(46));
    }

    @Test
    void cap_marksFailed() {
        SweepCandidate capped = candidate(QueuedSubmissionSweeper.MAX_REPUBLISHES, NOW.minus(Duration.ofMinutes(16)));
        candidates(capped);

        QueuedSubmissionSweeper.SweepResult result = sweeper.sweep(NOW);

        verify(submissions).markFailed(capped.id(), QueuedSubmissionSweeper.MAX_REPUBLISHES);
        verify(submissions, never()).recordRepublish(any(), anyInt(), any());
        verifyNoInteractions(publisher);
        assertThat(result).isEqualTo(new QueuedSubmissionSweeper.SweepResult(0, 1));
    }

    @Test
    void cap_waitsForTheLastRepublishFirst() {
        candidates(candidate(QueuedSubmissionSweeper.MAX_REPUBLISHES, NOW.minus(Duration.ofMinutes(10))));

        sweeper.sweep(NOW);

        verify(submissions, never()).markFailed(any(), anyInt());
    }

    @Test
    void rowChangedMeanwhile_notCounted() {
        // Analysed (or swept) between the read and the update: the compare-and-set changes nothing. The extra event
        // is harmless: the consumer recognises its event ID (D-28).
        SweepCandidate raced = candidate(0, NOW.minus(Duration.ofMinutes(5)));
        candidates(raced);
        when(submissions.recordRepublish(raced.id(), 0, NOW)).thenReturn(0);

        assertThat(sweeper.sweep(NOW)).isEqualTo(new QueuedSubmissionSweeper.SweepResult(0, 0));
    }

    @Test
    void publishFails_stopsThisSweep() {
        SweepCandidate first = candidate(0, NOW.minus(Duration.ofMinutes(9)));
        SweepCandidate second = candidate(0, NOW.minus(Duration.ofMinutes(5)));
        candidates(first, second);
        when(publisher.publish(first.toQueued())).thenReturn(false);

        sweeper.sweep(NOW);

        verify(publisher, never()).publish(second.toQueued());
        verify(submissions, never()).recordRepublish(eq(second.id()), anyInt(), any());
    }

    @Test
    void publishFails_attemptNotCounted() {
        // P13 (silent failure 2): a re-publish counts only once the broker confirmed it, so a Kafka outage longer
        // than the 46-minute schedule never marks undelivered submissions FAILED; the next sweep tries again.
        SweepCandidate stale = candidate(1, NOW.minus(Duration.ofMinutes(5)));
        candidates(stale);
        when(publisher.publish(stale.toQueued())).thenReturn(false);

        assertThat(sweeper.sweep(NOW)).isEqualTo(new QueuedSubmissionSweeper.SweepResult(0, 0));

        verify(submissions, never()).recordRepublish(any(), anyInt(), any());
        verify(submissions, never()).markFailed(any(), anyInt());
    }

    @Test
    void disabled_runDoesNothing() {
        new QueuedSubmissionSweeper(submissions, publisher, new NoOpTransactionManager(), false).run();

        verifyNoInteractions(submissions, publisher);
    }

    @Test
    void run_usesTheClockAndSurvivesErrors() {
        when(submissions.findSweepCandidates(any(), any())).thenThrow(new IllegalStateException("db down"));

        sweeper.run();

        verify(submissions).findSweepCandidates(any(), any());
    }

    private void candidates(SweepCandidate... candidates) {
        when(submissions.findSweepCandidates(any(), any())).thenReturn(List.of(candidates));
    }

    private static SweepCandidate candidate(int attempts, Instant lastPublishedAt) {
        return new SweepCandidate(UUID.randomUUID(), UUID.randomUUID(), null, SourceType.TEXT, "b".repeat(64),
                NOW.minus(Duration.ofHours(1)), attempts, lastPublishedAt);
    }
}
