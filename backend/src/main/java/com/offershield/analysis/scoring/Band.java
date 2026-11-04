package com.offershield.analysis.scoring;

/** PRD §7.2 bands, with their messages shown verbatim (CLAUDE.md product rules). */
public enum Band {
    LOW("No major red flags found. This does not prove the offer is genuine."),
    MEDIUM("Warning signs. Verify through the company's official website or careers page."),
    HIGH("Strong scam indicators. Do not pay anything.");

    static final int MEDIUM_FROM = 25;
    static final int HIGH_FROM = 50;

    private final String message;

    Band(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /** 0–24 Low, 25–49 Medium, 50 and above High. */
    static Band forPoints(int points) {
        if (points >= HIGH_FROM) {
            return HIGH;
        }
        return points >= MEDIUM_FROM ? MEDIUM : LOW;
    }
}
