package com.offershield.submission;

import java.time.Instant;
import java.util.UUID;

/**
 * What {@code submission.created} announces for one submission, handed to {@link SubmissionEventPublisher} after the
 * transaction that saved (or re-queued) it has committed, so Kafka never sees a rolled-back row. IDs and metadata only.
 */
public record SubmissionQueued(UUID submissionId, UUID ownerUserId, UUID collegeId, SourceType sourceType,
                               String contentSha256, Instant createdAt) {
}
