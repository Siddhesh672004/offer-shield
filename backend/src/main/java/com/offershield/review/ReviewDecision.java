package com.offershield.review;

/** A TPO's decision on an OPEN review case (PRD §4 F5); each is the case's final status. */
public enum ReviewDecision {
    CONFIRMED_SCAM,
    CONFIRMED_GENUINE,
    INCONCLUSIVE;

    ReviewStatus status() {
        return ReviewStatus.valueOf(name());
    }
}
