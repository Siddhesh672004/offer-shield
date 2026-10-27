package com.offershield.submission;

import java.time.Instant;
import java.util.UUID;

/** The non-secret columns of a submission, read without loading the encrypted text. */
public record SubmissionView(UUID id, SubmissionStatus status, SourceType sourceType, UUID collegeId,
                             String cloudinaryPublicId, Instant createdAt, Instant updatedAt, Instant expiresAt) {
}
