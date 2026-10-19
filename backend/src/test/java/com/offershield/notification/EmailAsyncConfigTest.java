package com.offershield.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.offershield.common.logging.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The email pool: small and bounded, never blocks or fails the committing thread, keeps the request ID in logs. */
class EmailAsyncConfigTest {

    private final ThreadPoolTaskExecutor executor = new EmailAsyncConfig().emailExecutor();

    @AfterEach
    void shutDown() {
        executor.shutdown();
        MDC.clear();
    }

    @Test
    void emailExecutor_boundedPool_core1Max2Queue100() {
        executor.initialize();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isEqualTo(100);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("email-");
    }

    @Test
    void submit_requestIdInCallerMdc_copiedToEmailThreadThenCleared() throws Exception {
        executor.initialize();
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> leftOver = new AtomicReference<>("not-run");
        MDC.put(LogContext.REQUEST_ID, "req-123");

        executor.submit(() -> seen.set(MDC.get(LogContext.REQUEST_ID))).get(5, TimeUnit.SECONDS);
        MDC.clear();
        // Core size 1: the same pool thread runs the next task.
        executor.submit(() -> leftOver.set(MDC.get(LogContext.REQUEST_ID))).get(5, TimeUnit.SECONDS);

        assertThat(seen.get()).isEqualTo("req-123");
        assertThat(leftOver.get()).as("no request ID left behind on the pool thread").isNull();
    }

    @Test
    void submit_queueFull_droppedAndLoggedWithoutThrowing() throws Exception {
        executor.initialize();
        Logger logger = (Logger) LoggerFactory.getLogger(EmailAsyncConfig.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 2 running (core 1 + max 2) + 100 queued fill the pool; the next one is rejected.
            for (int i = 0; i < 102; i++) {
                executor.execute(() -> awaitQuietly(release));
            }

            assertThatCode(() -> executor.execute(() -> { })).doesNotThrowAnyException();

            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("email dropped: queue full");
        } finally {
            release.countDown();
            logger.detachAppender(logs);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
