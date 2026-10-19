package com.offershield.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EmailServiceTest {

    private static final String STUDENT_EMAIL = "fake.student@example.com";
    private static final String REASON = "Roll number R-777 is not on our list";
    private static final EmailRequested EMAIL = new EmailRequested(STUDENT_EMAIL, "OfferShield: your request",
            "Your request to join Example College was not approved. Reason: " + REASON);

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void attachAppender() {
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(logs);
    }

    @Test
    void send_configured_plainTextFromSmtpAccount() {
        EmailService service = new EmailService(mailSender, "offershield.mailer@example.com");

        service.send(EMAIL);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("offershield.mailer@example.com");
        assertThat(sent.getValue().getTo()).containsExactly(STUDENT_EMAIL);
        assertThat(sent.getValue().getSubject()).isEqualTo("OfferShield: your request");
        assertThat(sent.getValue().getText()).isEqualTo(EMAIL.body());
        assertNoPersonalDataLogged();
    }

    @Test
    void smtpFailure_loggedDecisionKept() {
        doThrow(new MailSendException("Failed to send to " + STUDENT_EMAIL + ": " + REASON))
                .when(mailSender).send(any(SimpleMailMessage.class));
        EmailService service = new EmailService(mailSender, "offershield.mailer@example.com");

        // The caller (an after-commit listener) must never see the failure: the decision is already committed.
        assertThatCode(() -> service.send(EMAIL)).doesNotThrowAnyException();

        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("email failed").contains(MailSendException.class.getName());
                    assertThat(event.getThrowableProxy()).isNull();
                });
        assertNoPersonalDataLogged();
    }

    @Test
    void smtpNotConfigured_skipped() {
        EmailService service = new EmailService(mailSender, " ");

        service.send(EMAIL);

        verifyNoInteractions(mailSender);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("email skipped: SMTP not configured");
        assertNoPersonalDataLogged();
    }

    @Test
    void toString_hidesRecipientAndBody() {
        assertThat(EMAIL.toString()).doesNotContain(STUDENT_EMAIL).doesNotContain(REASON);
    }

    private void assertNoPersonalDataLogged() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(STUDENT_EMAIL).doesNotContain(REASON).doesNotContain("R-777");
            if (event.getThrowableProxy() != null) {
                assertThat(event.getThrowableProxy().getMessage()).doesNotContain(STUDENT_EMAIL);
            }
        });
    }
}
