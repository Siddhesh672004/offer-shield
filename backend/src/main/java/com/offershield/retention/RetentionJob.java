package com.offershield.retention;

import com.offershield.common.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Runs the 30-day cleanup (D-06) once a day, by default at 03:30 India time ({@code offershield.retention.cron} /
 * {@code .zone}), when traffic is lowest. {@code offershield.retention.enabled=false} turns the schedule off (the
 * tests call {@link RetentionService#run} on demand). Each run gets its own request ID for the logs; a failed run is
 * logged by exception class only and the next day's run continues where it stopped.
 */
@Component
@ConditionalOnProperty(prefix = "offershield.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService retention;

    public RetentionJob(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${offershield.retention.cron:0 30 3 * * *}", zone = "${offershield.retention.zone:Asia/Kolkata}")
    public void run() {
        MDC.put(LogContext.REQUEST_ID, UUID.randomUUID().toString());
        try {
            retention.run(Instant.now());
        } catch (RuntimeException ex) {
            log.warn("retention run failed: {}", ex.getClass().getSimpleName());
        } finally {
            MDC.remove(LogContext.REQUEST_ID);
        }
    }
}
