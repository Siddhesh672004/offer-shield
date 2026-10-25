package com.offershield.submission;

import java.util.UUID;

/** Body of 202 Accepted for {@code POST /api/submissions}; a replayed Idempotency-Key returns the same ID. */
public record SubmissionAccepted(UUID submissionId) {
}
