package com.offershield.analysis;

/**
 * The broker did not confirm {@code analysis.completed}. Thrown after the report was saved, so the container retries
 * the {@code submission.created} record; the redelivery is a duplicate and re-sends the stored report (D-28). If every
 * retry fails, the sweeper re-publishes the still-QUEUED submission later with the same effect.
 */
public class CompletionNotSentException extends RuntimeException {

    public CompletionNotSentException() {
        super("analysis.completed was not confirmed by the broker");
    }
}
