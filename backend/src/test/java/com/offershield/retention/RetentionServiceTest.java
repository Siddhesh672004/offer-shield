package com.offershield.retention;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.offershield.ai.LlmCache;
import com.offershield.analysis.ReportService;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.review.ReviewService;
import com.offershield.scamreport.ScamReportService;
import com.offershield.submission.ExpiredSubmission;
import com.offershield.submission.SubmissionRetention;
import com.offershield.support.LogCapture;
import com.offershield.support.NoOpTransactionManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retention orchestrator without a database (P11-01, D-06): one run at a time, PDFs deleted outside any
 * transaction before the batch is wiped in one, the snapshot hold rule, D-06 cut-offs, batched deletes, a storage
 * outage that stops further PDF attempts for the run, and one failing step that does not stop the others.
 */
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T22:00:00Z");

    private final SubmissionRetention submissions = mock(SubmissionRetention.class);
    private final ReportService reports = mock(ReportService.class);
    private final ReportSnapshotService snapshots = mock(ReportSnapshotService.class);
    private final ReviewService reviews = mock(ReviewService.class);
    private final ScamReportService scamReports = mock(ScamReportService.class);
    private final LlmCache llmCache = mock(LlmCache.class);
    private final ProcessedEvents processedEvents = mock(ProcessedEvents.class);
    private final NoOpTransactionManager transactionManager = new NoOpTransactionManager();
    private final RetentionService service = new RetentionService(submissions, reports, snapshots, reviews,
            scamReports, llmCache, processedEvents, transactionManager);

    @Test
    void run_whileAnotherRunActive_skipped() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(submissions.findExpired(any(), any(), anyInt())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<RetentionResult>> first = pool.submit(() -> service.run(NOW));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.run(NOW)).as("an overlapping run does nothing").isEmpty();

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(service.run(NOW)).as("the next run is allowed again").isPresent();
    }

    @Test
    void submissionBatch_pdfsDeletedOutsideTransaction_thenWipedTogetherInOne() {
        ExpiredSubmission withPdf = new ExpiredSubmission(UUID.randomUUID(), "submissions/a");
        ExpiredSubmission pasted = new ExpiredSubmission(UUID.randomUUID(), null);
        when(submissions.findExpired(eq(NOW), any(), anyInt())).thenReturn(List.of(withPdf, pasted));
        List<Boolean> transactionActive = new ArrayList<>();
        when(submissions.deletePdf("submissions/a")).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        when(reports.deleteForSubmissions(any())).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return 2;
        });
        when(submissions.wipe(any(), any(), eq(NOW))).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return 2;
        });

        RetentionResult result = service.run(NOW).orElseThrow();

        InOrder order = inOrder(submissions, reports);
        order.verify(submissions).deletePdf("submissions/a");
        order.verify(reports).deleteForSubmissions(List.of(withPdf.id(), pasted.id()));
        order.verify(submissions).wipe(List.of(withPdf.id(), pasted.id()), List.of(withPdf.id()), NOW);
        assertThat(transactionActive).containsExactly(false, true, true);
        assertThat(result.submissionsWiped()).isEqualTo(2);
        assertThat(result.reportsDeleted()).isEqualTo(2);
        assertThat(result.pdfsDeleted()).isEqualTo(1);
        assertThat(result.pdfDeleteFailures()).isZero();
    }

    @Test
    void pdfDeletesFailThreeTimesInARow_restOfRunNotTried() {
        List<ExpiredSubmission> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(new ExpiredSubmission(UUID.randomUUID(), "submissions/" + i));
        }
        when(submissions.findExpired(eq(NOW), any(), anyInt())).thenReturn(rows);
        when(submissions.deletePdf(anyString())).thenReturn(false);

        RetentionResult result = service.run(NOW).orElseThrow();

        verify(submissions, times(RetentionService.MAX_PDF_FAILURES_IN_A_ROW)).deletePdf(anyString());
        verify(submissions).wipe(rows.stream().map(ExpiredSubmission::id).toList(), List.of(), NOW);
        assertThat(result.pdfDeleteFailures()).isEqualTo(5);
        assertThat(result.pdfsDeleted()).isZero();
    }

    @Test
    void fullBatch_nextBatchStartsAfterItsLastId() {
        List<ExpiredSubmission> fullBatch = new ArrayList<>();
        for (int i = 0; i < RetentionService.SUBMISSION_BATCH; i++) {
            fullBatch.add(new ExpiredSubmission(UUID.randomUUID(), null));
        }
        UUID lastId = fullBatch.getLast().id();
        when(submissions.findExpired(NOW, RetentionService.FIRST_ID, RetentionService.SUBMISSION_BATCH))
                .thenReturn(fullBatch);
        when(submissions.findExpired(NOW, lastId, RetentionService.SUBMISSION_BATCH)).thenReturn(List.of());

        service.run(NOW).orElseThrow();

        verify(submissions).findExpired(NOW, lastId, RetentionService.SUBMISSION_BATCH);
    }

    @Test
    void failingSubmissionBatch_skippedAndLogged_laterBatchesStillWiped() {
        // P13 (silent failure 15): paging restarts at the first ID every run, so a batch that always fails would
        // otherwise stop every later expired letter from ever being wiped.
        List<ExpiredSubmission> failing = new ArrayList<>();
        for (int i = 0; i < RetentionService.SUBMISSION_BATCH; i++) {
            failing.add(new ExpiredSubmission(UUID.randomUUID(), null));
        }
        ExpiredSubmission later = new ExpiredSubmission(UUID.randomUUID(), null);
        when(submissions.findExpired(NOW, RetentionService.FIRST_ID, RetentionService.SUBMISSION_BATCH))
                .thenReturn(failing);
        when(submissions.findExpired(NOW, failing.getLast().id(), RetentionService.SUBMISSION_BATCH))
                .thenReturn(List.of(later));
        when(reports.deleteForSubmissions(failing.stream().map(ExpiredSubmission::id).toList()))
                .thenThrow(new DataAccessResourceFailureException("row-secret"));
        when(submissions.wipe(List.of(later.id()), List.of(), NOW)).thenReturn(1);

        RetentionResult result;
        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(RetentionService.class)) {
            result = service.run(NOW).orElseThrow();
            events = logs.events();
        }

        verify(submissions).wipe(List.of(later.id()), List.of(), NOW);
        assertThat(result.submissionsWiped()).isEqualTo(1);
        assertThat(result.failedSteps()).isZero();
        assertThat(result.failedBatches()).isEqualTo(1);
        assertThat(events).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("retention batch skipped step=submissions error=DataAccessResourceFailureException");
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains("row-secret"));
    }

    @Test
    void failingSnapshotBatch_skipped_laterPagesStillDeleted() {
        List<UUID> failingPage = new ArrayList<>();
        for (int i = 0; i < RetentionService.SNAPSHOT_BATCH; i++) {
            failingPage.add(UUID.randomUUID());
        }
        UUID later = UUID.randomUUID();
        when(snapshots.findSubmissionIds(RetentionService.FIRST_ID, RetentionService.SNAPSHOT_BATCH))
                .thenReturn(failingPage);
        when(snapshots.findSubmissionIds(failingPage.getLast(), RetentionService.SNAPSHOT_BATCH))
                .thenReturn(List.of(later));
        when(submissions.expiredAmong(failingPage, NOW)).thenThrow(new DataAccessResourceFailureException("db"));
        when(submissions.expiredAmong(List.of(later), NOW)).thenReturn(Set.of(later));
        when(snapshots.delete(List.of(later))).thenReturn(1);

        RetentionResult result = service.run(NOW).orElseThrow();

        assertThat(result.snapshotsDeleted()).isEqualTo(1);
        assertThat(result.failedSteps()).isZero();
        assertThat(result.failedBatches()).isEqualTo(1);
    }

    @Test
    void snapshots_expiredAndUnneededDeleted_heldOnesKept() {
        UUID notExpired = UUID.randomUUID();
        UUID heldByCase = UUID.randomUUID();
        UUID heldByScamReport = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        List<UUID> page = List.of(notExpired, heldByCase, heldByScamReport, free);
        when(snapshots.findSubmissionIds(RetentionService.FIRST_ID, RetentionService.SNAPSHOT_BATCH)).thenReturn(page);
        when(submissions.expiredAmong(page, NOW)).thenReturn(Set.of(heldByCase, heldByScamReport, free));
        Instant decidedSince = NOW.minus(Duration.ofDays(30));
        when(reviews.stillNeedEvidence(any(), eq(decidedSince))).thenReturn(Set.of(heldByCase));
        when(scamReports.stillNeedEvidence(any(), eq(decidedSince))).thenReturn(Set.of(heldByScamReport));
        when(snapshots.delete(List.of(free))).thenReturn(1);

        RetentionResult result = service.run(NOW).orElseThrow();

        verify(snapshots).delete(List.of(free));
        assertThat(result.snapshotsDeleted()).isEqualTo(1);
        assertThat(result.snapshotsKept()).isEqualTo(2);
    }

    @Test
    void cutOffs_followD06() {
        service.run(NOW).orElseThrow();

        verify(scamReports).wipeDescriptionsDecidedBefore(NOW.minus(Duration.ofDays(30)),
                RetentionService.DELETE_BATCH);
        verify(llmCache).deleteCreatedBefore(NOW.minus(Duration.ofDays(30)), RetentionService.DELETE_BATCH);
        verify(processedEvents).deleteProcessedBefore(NOW.minus(Duration.ofDays(14)), RetentionService.DELETE_BATCH);
    }

    @Test
    void largeDelete_repeatedUntilAShortBatch() {
        int batch = RetentionService.DELETE_BATCH;
        when(llmCache.deleteCreatedBefore(any(), anyInt())).thenReturn(batch, batch, 7);

        RetentionResult result = service.run(NOW).orElseThrow();

        verify(llmCache, times(3)).deleteCreatedBefore(any(), anyInt());
        assertThat(result.llmCacheDeleted()).isEqualTo(2 * batch + 7);
    }

    @Test
    void stepFails_otherStepsStillRun_failureLoggedByClassOnly() {
        when(submissions.findExpired(any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down at host-secret"));
        when(llmCache.deleteCreatedBefore(any(), anyInt())).thenReturn(3);
        when(processedEvents.deleteProcessedBefore(any(), anyInt())).thenReturn(4);

        RetentionResult result;
        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(RetentionService.class)) {
            result = service.run(NOW).orElseThrow();
            events = logs.events();
        }

        assertThat(result.failedSteps()).isEqualTo(1);
        assertThat(result.llmCacheDeleted()).isEqualTo(3);
        assertThat(result.processedEventsDeleted()).isEqualTo(4);
        verify(snapshots).findSubmissionIds(any(), anyInt());
        assertThat(events).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("retention step failed step=submissions error=DataAccessResourceFailureException");
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains("host-secret"));
    }

    @Test
    void run_logsOneSummaryLineWithCounts() {
        when(llmCache.deleteCreatedBefore(any(), anyInt())).thenReturn(5);
        when(processedEvents.deleteProcessedBefore(any(), anyInt())).thenReturn(10);

        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(RetentionService.class)) {
            service.run(NOW).orElseThrow();
            events = logs.events();
        }

        assertThat(events).extracting(ILoggingEvent::getFormattedMessage).singleElement().asString()
                .matches("retention run: submissionsWiped=0 reportsDeleted=0 pdfsDeleted=0 pdfDeleteFailures=0 "
                        + "snapshotsDeleted=0 snapshotsKept=0 descriptionsWiped=0 llmCacheDeleted=5 "
                        + "processedEventsDeleted=10 failedSteps=0 failedBatches=0 durationMs=\\d+");
    }
}
