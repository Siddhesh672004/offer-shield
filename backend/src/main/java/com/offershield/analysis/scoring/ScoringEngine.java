package com.offershield.analysis.scoring;

import com.offershield.analysis.checks.CheckResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic scoring (PRD §2.1, §7.2): Java computes the score; AI never does. Points of triggered checks are
 * summed and capped at 100, then banded 0–24 / 25–49 / ≥ 50. A triggered check asking for a hard override forces
 * High only when it is C1, C10 or C11 ({@code CheckCode.canOverride()}): unverified reports (C12) and every other
 * check can never force High on their own (§7.3 abuse guard).
 */
@Component
public class ScoringEngine {

    static final int MAX_POINTS = 100;

    public Score score(List<CheckResult> results) {
        int points = Math.min(MAX_POINTS, results.stream()
                .filter(CheckResult::isTriggered)
                .mapToInt(CheckResult::points)
                .sum());
        boolean override = results.stream()
                .anyMatch(result -> result.isTriggered() && result.hardOverride() && result.code().canOverride());
        return new Score(points, override ? Band.HIGH : Band.forPoints(points), override);
    }
}
