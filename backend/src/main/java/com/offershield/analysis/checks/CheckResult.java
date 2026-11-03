package com.offershield.analysis.checks;

import java.util.Objects;

/**
 * One signal of the report (PRD §7, §10): what the check found, the points it adds, a plain-language explanation, the
 * evidence it found (≤ 200 characters, contact details masked) and the kind of source (1–5, PRD §7 table).
 * {@code hardOverride} asks for a forced High; the {@code ScoringEngine} honours it only for C1, C10 and C11.
 */
public record CheckResult(CheckCode code, CheckOutcome outcome, int points, boolean hardOverride, String explanation,
                          String evidence, int sourceKind, boolean aiExtracted) {

    public static final int MAX_EXPLANATION = 500;

    public CheckResult {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(outcome, "outcome");
        if (explanation == null || explanation.isBlank() || explanation.length() > MAX_EXPLANATION) {
            throw new IllegalArgumentException("explanation must be 1-" + MAX_EXPLANATION + " characters");
        }
        if (evidence != null && evidence.length() > Evidence.MAX_LENGTH) {
            throw new IllegalArgumentException("evidence must be at most " + Evidence.MAX_LENGTH + " characters");
        }
        if (sourceKind < 1 || sourceKind > 5) {
            throw new IllegalArgumentException("sourceKind must be 1-5");
        }
        if (points < 0 || points > 100) {
            throw new IllegalArgumentException("points must be 0-100");
        }
        if (outcome != CheckOutcome.TRIGGERED && (points != 0 || hardOverride)) {
            throw new IllegalArgumentException("only a triggered check adds points or overrides");
        }
    }

    public static CheckResult triggered(CheckCode code, int points, String explanation, String evidence, int sourceKind) {
        return new CheckResult(code, CheckOutcome.TRIGGERED, points, false, explanation, evidence, sourceKind, false);
    }

    /** A triggered check that forces High (C1, C10, C11 only); it adds no points of its own. */
    public static CheckResult override(CheckCode code, String explanation, String evidence, int sourceKind) {
        return new CheckResult(code, CheckOutcome.TRIGGERED, 0, true, explanation, evidence, sourceKind, false);
    }

    public static CheckResult passed(CheckCode code, String explanation, int sourceKind) {
        return new CheckResult(code, CheckOutcome.PASSED, 0, false, explanation, null, sourceKind, false);
    }

    public static CheckResult unavailable(CheckCode code, String explanation, int sourceKind) {
        return new CheckResult(code, CheckOutcome.UNAVAILABLE, 0, false, explanation, null, sourceKind, false);
    }

    public static CheckResult notApplicable(CheckCode code, String explanation, int sourceKind) {
        return new CheckResult(code, CheckOutcome.NOT_APPLICABLE, 0, false, explanation, null, sourceKind, false);
    }

    public boolean isTriggered() {
        return outcome == CheckOutcome.TRIGGERED;
    }
}
