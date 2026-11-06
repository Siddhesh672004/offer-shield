package com.offershield.ai;

/**
 * A plain-language explanation of a report: written by the AI ({@code aiWritten}) or, when the AI is unavailable or its
 * answer broke a rule, built from the signals by a fixed template (PRD §8.2 "No AI → template explanations").
 */
public record Explanation(String text, boolean aiWritten) {

    @Override
    public String toString() {
        return "Explanation[aiWritten=" + aiWritten + ", redacted]";
    }
}
