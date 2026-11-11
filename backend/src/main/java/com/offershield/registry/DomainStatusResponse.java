package com.offershield.registry;

/**
 * Answer of {@code GET /api/registry/domains/{domain}}: the registrable domain that was looked up (lower-case ASCII),
 * its {@link DomainStatus}, how many distinct people reported it (UNVERIFIED only, else {@code null}) and the text to
 * show. Never who reported it.
 */
public record DomainStatusResponse(String domain, DomainStatus status, Integer reporterCount, String message) {
}
