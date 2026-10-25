package com.offershield.submission;

import java.util.UUID;

/** What an earlier request with the same Idempotency-Key saved: its submission and the hash of its content. */
public record StoredRequest(UUID id, String requestSha256) {
}
