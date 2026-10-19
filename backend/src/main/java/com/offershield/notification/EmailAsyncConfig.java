package com.offershield.notification;

import com.offershield.common.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;

/**
 * Emails are sent on a small pool of their own, so an SMTP call (up to 10 s per timeout) never holds a request thread
 * or its database connection. The pool is bounded: 1 thread, a second only when 100 emails are already waiting, and
 * beyond that an email is dropped with a log line (best effort, D-22). The caller's request ID goes with each email
 * so its log lines can still be traced (D-27); nothing else is copied.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class EmailAsyncConfig {

    public static final String EMAIL_EXECUTOR = "emailExecutor";
    static final int QUEUE_CAPACITY = 100;
    private static final int SHUTDOWN_WAIT_SECONDS = 15;

    private static final Logger log = LoggerFactory.getLogger(EmailAsyncConfig.class);

    @Bean(name = EMAIL_EXECUTOR)
    ThreadPoolTaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("email-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setTaskDecorator(requestIdDecorator());
        executor.setRejectedExecutionHandler(dropWhenFull());
        // On shutdown, give queued emails a short chance to go out.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        return executor;
    }

    /** Copies only the request ID into the email thread and clears the MDC afterwards (pool threads are reused). */
    private static TaskDecorator requestIdDecorator() {
        return task -> {
            String requestId = MDC.get(LogContext.REQUEST_ID);
            return () -> {
                if (requestId != null) {
                    MDC.put(LogContext.REQUEST_ID, requestId);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }

    /** Runs on the committing thread: log (no address, no content) and return, so the decision's response is kept. */
    private static RejectedExecutionHandler dropWhenFull() {
        return (task, pool) -> log.warn("email dropped: queue full");
    }
}
