package com.offershield.notification;

/**
 * "Send this plain-text email once my transaction commits." Publish it with Spring's ApplicationEventPublisher
 * inside the transaction that makes the decision (D-22, D-33.7); a rollback means no email.
 */
public record EmailRequested(String to, String subject, String body) {

    /** Never expose the address or the text (D-27), even if a framework logs the event. */
    @Override
    public String toString() {
        return "EmailRequested[redacted]";
    }
}
