package com.offershield.analysis;

import com.offershield.ai.ExplainService;
import com.offershield.ai.Explanation;
import com.offershield.common.web.TooManyRequestsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P13 (security review S3): "Explain simply" must not let one account use up the shared Groq quota. Parallel clicks on
 * one report share one AI call; a template answer (AI unavailable) is reused for 10 minutes; at most 10 AI explanations
 * per user in 10 minutes (429 {@code EXPLAIN_LIMIT} with Retry-After). Saved explanations cost nothing.
 */
class ReportExplanationServiceTest {

    private static final UUID OWNER = UUID.fromString("0190c9a0-0000-7000-8000-0000000000e1");
    private static final UUID SUBMISSION = UUID.fromString("0190c9a0-0000-7000-8000-0000000000e2");
    private static final String TEMPLATE = "Strong scam indicators. Do not pay anything.\n\nWhat the checks found: …";
    private static final String AI_TEXT = "This letter asks you to pay a deposit, a strong scam sign. Never pay money "
            + "to get a job.";

    private final ReportService reports = mock(ReportService.class);
    private final ExplainService explainService = mock(ExplainService.class);
    private final ReportExplanationService service = new ReportExplanationService(reports, explainService);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @BeforeEach
    void defaults() {
        when(reports.explainSourceForOwner(eq(OWNER), any())).thenReturn(new ReportService.ExplainSource(null, null));
        when(reports.saveExplanation(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void concurrentClicks_oneAiCall_bothGetTheSameAnswer() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger aiCalls = new AtomicInteger();
        when(explainService.explain(any())).thenAnswer(invocation -> {
            aiCalls.incrementAndGet();
            asked.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new Explanation(AI_TEXT, true);
        });

        CompletableFuture<ExplanationResponse> first =
                CompletableFuture.supplyAsync(() -> service.explain(OWNER, SUBMISSION), pool);
        assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        CompletableFuture<ExplanationResponse> second = CompletableFuture.supplyAsync(() -> {
            secondThread.set(Thread.currentThread());
            return service.explain(OWNER, SUBMISSION);
        }, pool);
        await().atMost(Duration.ofSeconds(5)).until(() -> secondThread.get() != null
                && (secondThread.get().getState() == Thread.State.WAITING
                || secondThread.get().getState() == Thread.State.TIMED_WAITING));
        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS).explanation()).isEqualTo(AI_TEXT);
        assertThat(second.get(5, TimeUnit.SECONDS).explanation()).isEqualTo(AI_TEXT);
        assertThat(aiCalls).hasValue(1);
    }

    @Test
    void aiUnavailable_templateReusedForTenMinutes_noSecondAiCall() {
        when(explainService.explain(any())).thenReturn(new Explanation(TEMPLATE, false));

        ExplanationResponse first = service.explain(OWNER, SUBMISSION);
        ExplanationResponse again = service.explain(OWNER, SUBMISSION);

        assertThat(again).isEqualTo(first);
        assertThat(again.aiWritten()).isFalse();
        verify(explainService, times(1)).explain(any());
    }

    @Test
    void templateOlderThanTenMinutes_aiAskedAgain() {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        ReportExplanationService timed = new ReportExplanationService(reports, explainService, nanos::get);
        when(explainService.explain(any())).thenReturn(new Explanation(TEMPLATE, false), new Explanation(AI_TEXT, true));

        assertThat(timed.explain(OWNER, SUBMISSION).aiWritten()).isFalse();
        nanos.addAndGet(Duration.ofMinutes(10).plusSeconds(1).toNanos());

        assertThat(timed.explain(OWNER, SUBMISSION).explanation()).isEqualTo(AI_TEXT);
        verify(explainService, times(2)).explain(any());
    }

    @Test
    void eleventhAiExplanationInTenMinutes_429ExplainLimit_savedOnesStillServed() {
        when(explainService.explain(any())).thenReturn(new Explanation(AI_TEXT, true));
        for (int i = 0; i < 10; i++) {
            assertThat(service.explain(OWNER, UUID.randomUUID()).aiWritten()).isTrue();
        }

        assertThatThrownBy(() -> service.explain(OWNER, UUID.randomUUID()))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("EXPLAIN_LIMIT");
                    assertThat(ex.retryAfterSeconds()).isBetween(1L, 600L);
                });
        verify(explainService, times(10)).explain(any());

        UUID saved = UUID.randomUUID();
        when(reports.explainSourceForOwner(OWNER, saved)).thenReturn(new ReportService.ExplainSource(AI_TEXT, null));
        assertThat(service.explain(OWNER, saved).explanation()).as("a saved explanation needs no AI call")
                .isEqualTo(AI_TEXT);
        UUID otherUser = UUID.randomUUID();
        when(reports.explainSourceForOwner(eq(otherUser), any())).thenReturn(new ReportService.ExplainSource(null, null));
        assertThat(service.explain(otherUser, UUID.randomUUID()).aiWritten()).as("another user is not limited").isTrue();
    }
}
