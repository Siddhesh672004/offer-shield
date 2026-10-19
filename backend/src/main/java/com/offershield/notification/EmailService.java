package com.offershield.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Plain-text email over SMTP (Gmail in production, D-22). Best effort: a failure is logged by exception class
 * only and never thrown, because the decision that triggered the email is already committed. Addresses, names,
 * roll numbers and reasons are never logged (D-27).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(JavaMailSender mailSender, @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.from = from == null ? "" : from.trim();
    }

    public void send(EmailRequested email) {
        if (from.isEmpty()) {
            log.info("email skipped: SMTP not configured");
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());
        try {
            mailSender.send(message);
            log.info("email sent");
        } catch (RuntimeException ex) {
            // The message of a mail exception can contain the recipient address: log the class only.
            log.warn("email failed: {}", ex.getClass().getName());
        }
    }
}
