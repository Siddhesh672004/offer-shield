package com.offershield.analysis.scoring;

/**
 * The result of scoring: {@code points} is the sum of triggered checks' points, capped at 100; {@code band} follows
 * PRD §7.2; {@code hardOverride} says the band was forced to High by C1, C10 or C11, whatever the points.
 */
public record Score(int points, Band band, boolean hardOverride) {
}
