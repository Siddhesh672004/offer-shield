package com.offershield.review;

/** PRD §4 F5 / 02 §7: OPEN → CONFIRMED_SCAM / CONFIRMED_GENUINE / INCONCLUSIVE, decided once by a TPO. */
public enum ReviewStatus {
    OPEN,
    CONFIRMED_SCAM,
    CONFIRMED_GENUINE,
    INCONCLUSIVE
}
