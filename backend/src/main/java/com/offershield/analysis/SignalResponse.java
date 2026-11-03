package com.offershield.analysis;

import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckOutcome;

/**
 * One signal of a report (PRD §7): the check, its outcome and points, why it matters, the evidence it found
 * ({@code null} unless triggered) and the kind of source (1 the letter, 2 public registries, 3 curated lists,
 * 4 confirmed reports, 5 user reports). {@code aiExtracted} marks values found by the AI (from P7).
 */
public record SignalResponse(CheckCode code, String title, CheckOutcome outcome, int points, boolean hardOverride,
                             String explanation, String evidence, int sourceKind, boolean aiExtracted) {

    static SignalResponse from(ReportSignal signal) {
        return new SignalResponse(signal.getCheckCode(), signal.getCheckCode().title(), signal.getOutcome(),
                signal.getPoints(), signal.isHardOverride(), signal.getExplanation(), signal.getEvidence(),
                signal.getSourceKind(), signal.isAiExtracted());
    }
}
