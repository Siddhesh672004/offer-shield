package com.offershield.analysis.checks;

/** PRD §10 {@code signal.outcome}. UNAVAILABLE (a lookup failed or had no data) makes the report PARTIAL (D-31). */
public enum CheckOutcome {
    TRIGGERED,
    PASSED,
    UNAVAILABLE,
    NOT_APPLICABLE
}
