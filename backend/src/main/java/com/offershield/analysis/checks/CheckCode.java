package com.offershield.analysis.checks;

/**
 * The checks of PRD §7.1 (C7, email-header analysis, is deferred). Only C1, C10 and C11 may force a High band
 * (PRD §7.2, §18); every other check only adds points.
 */
public enum CheckCode {
    C1("Money request", 1),
    C2("Free email sender", 1),
    C3("Domain doesn't match the company", 3),
    C4("Lookalike domain", 3),
    C5("Young domain", 2),
    C6("Mail setup", 2),
    C8("Off-platform contact", 1),
    C9("Pressure language", 1),
    C10("Confirmed scam match", 4),
    C11("Copy of a confirmed scam letter", 4),
    C12("Reported by users (not verified)", 5);

    private final String title;
    private final int sourceKind;

    CheckCode(String title, int sourceKind) {
        this.title = title;
        this.sourceKind = sourceKind;
    }

    public String title() {
        return title;
    }

    /** The PRD §7.1 source kind of the check's evidence (C2's free-mail list is kind 3, but the address is kind 1). */
    public int sourceKind() {
        return sourceKind;
    }

    /** PRD §7.2: a hard override to High is possible only for C1, C10 and C11. */
    public boolean canOverride() {
        return this == C1 || this == C10 || this == C11;
    }
}
