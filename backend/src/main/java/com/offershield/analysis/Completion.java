package com.offershield.analysis;

import com.offershield.common.events.AnalysisCompletedEvent;

import java.time.Instant;

/** A stored report as the {@code analysis.completed} payload, with the time the report was saved. */
public record Completion(AnalysisCompletedEvent.Payload payload, Instant completedAt) {
}
