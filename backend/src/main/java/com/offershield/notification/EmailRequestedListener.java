package com.offershield.notification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends a requested email only after the publishing transaction has committed, so a rolled-back decision never
 * emails anyone. Runs on the committing thread; {@link EmailService} swallows and logs failures, so the caller
 * still gets its response and the committed decision stands. Delivery is best effort (no retry), per D-22.
 */
@Component
class EmailRequestedListener {

    private final EmailService emailService;

    EmailRequestedListener(EmailService emailService) {
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEmailRequested(EmailRequested email) {
        emailService.send(email);
    }
}
