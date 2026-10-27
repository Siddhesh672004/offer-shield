package com.offershield.submission;

/**
 * Lifecycle (02 §7): QUEUED → READY / PARTIAL when analysis completes; QUEUED → FAILED when the sweeper gives up
 * (D-28); any state → DELETED by the 30-day retention job (D-06; a row still QUEUED after 30 days is wiped too).
 * DELETED is final: the row stays (cases, reports and registry reporters refer to it) without the letter's text,
 * contact details, sender domain or PDF.
 */
public enum SubmissionStatus {
    QUEUED,
    READY,
    PARTIAL,
    FAILED,
    DELETED
}
