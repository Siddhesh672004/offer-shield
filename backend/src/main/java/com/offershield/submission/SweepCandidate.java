package com.offershield.submission;

import java.time.Instant;
import java.util.UUID;

/** A QUEUED submission the sweeper may re-publish or give up on (D-28). */
public record SweepCandidate(UUID id, UUID ownerUserId, UUID collegeId, SourceType sourceType, String contentSha256,
                             Instant createdAt, int publishAttempts, Instant lastPublishedAt) {

    SubmissionQueued toQueued() {
        return new SubmissionQueued(id, ownerUserId, collegeId, sourceType, contentSha256, createdAt);
    }
}
