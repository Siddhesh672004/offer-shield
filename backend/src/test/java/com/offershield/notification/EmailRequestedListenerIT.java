package com.offershield.notification;

import com.offershield.support.MailIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** D-22 / D-33.7: an email is sent over real SMTP only after the publishing transaction commits. */
class EmailRequestedListenerIT extends MailIntegrationTest {

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void committed_sentOnceAfterCommit() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(new EmailRequested("listener.commit@example.com", "Test subject", "Test body"));
            assertThat(GREEN_MAIL.getReceivedMessages()).as("nothing is sent before commit").isEmpty();
        });

        assertThat(GREEN_MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()).extracting(Object::toString).containsExactly("listener.commit@example.com");
        assertThat(received[0].getFrom()).extracting(Object::toString).containsExactly(SMTP_USER);
        assertThat(received[0].getSubject()).isEqualTo("Test subject");
        assertThat(received[0].getContent().toString()).contains("Test body");
    }

    @Test
    void rolledBack_notSent() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(new EmailRequested("listener.rollback@example.com", "Test subject", "Test body"));
            status.setRollbackOnly();
        });

        assertThat(GREEN_MAIL.waitForIncomingEmail(1_000, 1)).isFalse();
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }

    @Test
    void publishedOutsideTransaction_notSent() {
        publisher.publishEvent(new EmailRequested("listener.notx@example.com", "Test subject", "Test body"));

        assertThat(GREEN_MAIL.waitForIncomingEmail(1_000, 1)).isFalse();
    }
}
