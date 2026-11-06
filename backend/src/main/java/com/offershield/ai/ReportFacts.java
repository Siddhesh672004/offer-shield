package com.offershield.ai;

import java.util.List;

/**
 * What "Explain simply" may talk about (PRD §4 F4, §8.1): the finished report's band, score and signals, never the
 * letter itself. {@code bandMessage} is the PRD §7.2 text; each {@link Fact} is one signal with its plain-language
 * explanation, its evidence (already masked) and its source kind (1–5).
 */
public record ReportFacts(String band, String bandMessage, int score, boolean hardOverride, List<Fact> facts) {

    public ReportFacts {
        facts = List.copyOf(facts);
    }

    public record Fact(String code, String title, String outcome, int points, String explanation, String evidence,
                       int sourceKind, boolean aiExtracted) {

        public boolean triggered() {
            return "TRIGGERED".equals(outcome);
        }

        public boolean unavailable() {
            return "UNAVAILABLE".equals(outcome);
        }
    }

    @Override
    public String toString() {
        return "ReportFacts[band=" + band + ", facts=" + facts.size() + ", redacted]";
    }
}
