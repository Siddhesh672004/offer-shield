package com.offershield.registry;

/**
 * What the registry knows about a sender domain (PRD §11, {@code GET /api/registry/domains/{domain}}): CONFIRMED
 * ("Confirmed scam"), UNVERIFIED ("Reported as a scam by N users (not verified)"), NONE (no live marker), or
 * NOT_TRACKED (a free-mail or known company's official domain, which never becomes a marker, D-08).
 */
public enum DomainStatus {
    CONFIRMED,
    UNVERIFIED,
    NONE,
    NOT_TRACKED
}
