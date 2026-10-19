package com.offershield.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

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

    @BeforeEach
    void purgeMailboxes() throws FolderException {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
