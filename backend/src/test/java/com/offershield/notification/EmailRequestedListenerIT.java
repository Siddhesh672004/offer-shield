package com.offershield.notification;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.offershield.common.logging.LogContext;
import com.offershield.support.LogCapture;
import com.offershield.support.MailIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * D-22 / D-33.7: an email is sent over real SMTP only after the publishing transaction commits, on the email pool
 * (never the committing request thread), with the request ID kept in its log lines.
 */
class EmailRequestedListenerIT extends MailIntegrationTest {

    private static final Duration DELIVERY = Duration.ofSeconds(10);
    private static final Duration QUIET = Duration.ofSeconds(1);

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationContext context;

    @Test
    void committed_sentOnceAfterCommit() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(new EmailRequested("listener.commit@example.com", "Test subject", "Test body"));
            assertThat(GREEN_MAIL.getReceivedMessages()).as("nothing is sent before commit").isEmpty();
        });

        await().atMost(DELIVERY).until(() -> GREEN_MAIL.getReceivedMessages().length == 1);
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()).extracting(Object::toString).containsExactly("listener.commit@example.com");
        assertThat(received[0].getFrom()).extracting(Object::toString).containsExactly(SMTP_USER);
        assertThat(received[0].getSubject()).isEqualTo("Test subject");
        assertThat(received[0].getContent().toString()).contains("Test body");
    }

    @Test
    void committed_sentOnEmailThreadWithCallersRequestId() {
        try (LogCapture logs = LogCapture.of(EmailService.class)) {
            MDC.put(LogContext.REQUEST_ID, "it-request-42");
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> publisher.publishEvent(
                        new EmailRequested("listener.async@example.com", "Test subject", "Test body")));
            } finally {
                MDC.remove(LogContext.REQUEST_ID);
            }

            await().atMost(DELIVERY).until(() -> GREEN_MAIL.getReceivedMessages().length == 1);
            await().atMost(DELIVERY).untilAsserted(() -> assertThat(logs.events())
                    .filteredOn(event -> event.getFormattedMessage().equals("email sent"))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getThreadName()).startsWith("email-");
                        assertThat(event.getMDCPropertyMap()).containsEntry(LogContext.REQUEST_ID, "it-request-42");
                    }));
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(line -> line.contains("listener.async@example.com"));
        }
    }

    /** The email pool must not replace Boot's default executor, or a plain @Async would land on the email pool. */
    @Test
    void context_bootDefaultExecutorKeptNextToEmailPool() {
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(context.containsBean(EmailAsyncConfig.EMAIL_EXECUTOR)).isTrue();
    }

    @Test
    void rolledBack_notSent() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(new EmailRequested("listener.rollback@example.com", "Test subject", "Test body"));
            status.setRollbackOnly();
        });

        await().during(QUIET).atMost(QUIET.plusSeconds(1)).until(() -> GREEN_MAIL.getReceivedMessages().length == 0);
    }

    @Test
    void publishedOutsideTransaction_notSent() {
        publisher.publishEvent(new EmailRequested("listener.notx@example.com", "Test subject", "Test body"));

        await().during(QUIET).atMost(QUIET.plusSeconds(1)).until(() -> GREEN_MAIL.getReceivedMessages().length == 0);
    }
}
