package com.offershield.retention;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.offershield.common.logging.LogContext;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The daily trigger of the retention job (P11-01, D-06): scheduled at 03:30 India time by default (configurable), off
 * when {@code offershield.retention.enabled=false} (the tests run the job on demand), each run with its own request ID,
 * and a failed run is logged by exception class and never thrown into the scheduler.
 */
class RetentionJobScheduleTest {

    private final RetentionService service = mock(RetentionService.class);
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(RetentionService.class, () -> service)
            .withUserConfiguration(RetentionJob.class);

    @Test
    void enabledByDefault_jobPresent() {
        contexts.run(context -> assertThat(context).hasSingleBean(RetentionJob.class));
    }

    @Test
    void disabled_noJob() {
        contexts.withPropertyValues("offershield.retention.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RetentionJob.class));
    }

    @Test
    void schedule_dailyAt0330IndiaByDefault() throws Exception {
        Scheduled scheduled = RetentionJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${offershield.retention.cron:0 30 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${offershield.retention.zone:Asia/Kolkata}");
        ZonedDateTime next = CronExpression.parse("0 30 3 * * *")
                .next(ZonedDateTime.of(LocalDateTime.parse("2026-10-20T12:00:00"), ZoneId.of("Asia/Kolkata")));
        assertThat(next).isEqualTo(ZonedDateTime.of(LocalDateTime.parse("2026-10-21T03:30:00"),
                ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void run_usesCurrentTimeAndItsOwnRequestId() {
        List<Instant> times = new ArrayList<>();
        List<String> requestIds = new ArrayList<>();
        when(service.run(any())).thenAnswer(invocation -> {
            times.add(invocation.getArgument(0));
            requestIds.add(MDC.get(LogContext.REQUEST_ID));
            return Optional.empty();
        });

        new RetentionJob(service).run();

        assertThat(times).singleElement().satisfies(time ->
                assertThat(time).isCloseTo(Instant.now(), within(Duration.ofSeconds(5))));
        assertThat(requestIds).singleElement().asString().hasSize(36);
        assertThat(MDC.get(LogContext.REQUEST_ID)).as("removed after the run").isNull();
    }

    @Test
    void run_failure_loggedByClassNotThrown() {
        when(service.run(any())).thenThrow(new IllegalStateException("contains-a-secret"));

        try (LogCapture logs = LogCapture.of(RetentionJob.class)) {
            new RetentionJob(service).run();

            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("retention run failed: IllegalStateException");
        }
    }
}
