package com.offershield.analysis;

import java.util.UUID;

/**
 * {@code POST /api/reports/{submissionId}/explain} (P7-03, PRD §4 F4). {@code explanation} is plain text (line breaks
 * allowed; render as text, never as HTML). {@code aiWritten = true}: written by the AI from the report's signals and
 * saved, so every later call returns the same text; label it "AI-written summary". {@code aiWritten = false}: the AI is
 * unavailable, so the text was built from the signals by a fixed template; it is not saved, and a later call tries
 * the AI again.
 */
public record ExplanationResponse(UUID submissionId, String explanation, boolean aiWritten) {
}
