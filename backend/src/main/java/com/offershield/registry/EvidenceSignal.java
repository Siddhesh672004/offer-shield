package com.offershield.registry;

/**
 * One signal of a report as a TPO or the admin sees it (D-04), copied from the report snapshot: the check, its
 * title, outcome and points, why it matters, the evidence it found (masked contacts, at most 200 characters) and the
 * kind of source (1 the letter … 5 user reports). {@code aiExtracted} marks values the AI found.
 */
public record EvidenceSignal(String code, String title, String outcome, int points, boolean hardOverride,
                             String explanation, String evidence, int sourceKind, boolean aiExtracted) {
}
