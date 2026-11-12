package com.offershield.registry;

/**
 * Trust level of a scam marker (PRD §6.4, §7 source kinds 4 and 5; 02 §7). UNVERIFIED markers come from user reports
 * and feed only C12 ("Reported as a scam by N users (not verified)"); CONFIRMED markers were confirmed by the admin or
 * a TPO and force High through C10/C11 (R-B); REJECTED markers have no reporter left and are ignored.
 */
public enum IndicatorStatus {
    UNVERIFIED,
    CONFIRMED,
    REJECTED
}
