package com.offershield.registry;

/**
 * The kinds of scam marker in the registry (PRD §4 F7, §10): a sender's registrable domain, the HMAC key of a phone
 * number or UPI ID, or a letter's SimHash wording fingerprint.
 */
public enum IndicatorType {
    DOMAIN,
    PHONE,
    UPI,
    SIMHASH
}
