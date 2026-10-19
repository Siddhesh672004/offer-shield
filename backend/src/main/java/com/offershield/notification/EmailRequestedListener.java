package com.offershield.notification;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends a requested email only after the publishing transaction has committed, so a rolled-back decision never
 * emails anyone. The send itself runs on the email pool ({@link EmailAsyncConfig}): the request thread returns its
 * response straight away and doesn't hold its database connection during SMTP. {@link EmailService} swallows and
 * logs failures, so the committed decision always stands. Delivery is best effort (no retry), per D-22.
 */
@Component
class EmailRequestedListener {

    private final EmailService emailService;

    EmailRequestedListener(EmailService emailService) {
        this.emailService = emailService;
    }

    @Async(EmailAsyncConfig.EMAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailRequested(EmailRequested email) {
        emailService.send(email);
    }
}
