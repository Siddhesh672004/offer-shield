package com.offershield.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.offershield.notification.EmailAsyncConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

/**
 * {@link IntegrationTest} plus a real SMTP server (GreenMail) that the application's JavaMailSender talks to.
 * The port is picked once per JVM, so every subclass shares one cached Spring context. Fake credentials only.
 */
public abstract class MailIntegrationTest extends IntegrationTest {

    protected static final String SMTP_USER = "offershield.mailer@example.com";
    private static final String SMTP_PASSWORD = "greenmail-fake-password";
    private static final int SMTP_PORT = freePort();

    @RegisterExtension
    protected static final GreenMailExtension GREEN_MAIL =
            new GreenMailExtension(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
                    .withConfiguration(GreenMailConfiguration.aConfig().withUser(SMTP_USER, SMTP_PASSWORD))
                    .withPerMethodLifecycle(false);

    @DynamicPropertySource
    static void smtpProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SMTP_PORT);
        registry.add("spring.mail.username", () -> SMTP_USER);
        registry.add("spring.mail.password", () -> SMTP_PASSWORD);
        registry.add("spring.mail.properties[mail.smtp.auth]", () -> "true");
        registry.add("spring.mail.properties[mail.smtp.starttls.enable]", () -> "false");
        registry.add("spring.mail.properties[mail.smtp.starttls.required]", () -> "false");
    }

    @Autowired
    @Qualifier(EmailAsyncConfig.EMAIL_EXECUTOR)
    private ThreadPoolTaskExecutor emailExecutor;

    /** Emails from an earlier test may still be in flight on the email pool; let them land before purging. */
    @BeforeEach
    void purgeMailboxes() throws Exception {
        awaitEmailsSent();
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }

    /**
     * Waits until every email handed to the email pool so far has been sent (or has failed). The pool has one core
     * thread and the queue is never full in tests, so tasks run in submission order: once a no-op submitted now has
     * run, so has everything before it.
     */
    protected void awaitEmailsSent() throws Exception {
        emailExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
