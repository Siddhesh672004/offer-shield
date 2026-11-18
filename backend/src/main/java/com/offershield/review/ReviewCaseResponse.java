package com.offershield.review;

import java.time.Instant;
import java.util.UUID;

/** The student's own view of a review case: its state and the TPO's note once decided. */
public record ReviewCaseResponse(UUID id, UUID submissionId, ReviewStatus status, String note, Instant createdAt,
                                 Instant decidedAt) {

    static ReviewCaseResponse from(ReviewCase reviewCase) {
        return new ReviewCaseResponse(reviewCase.getId(), reviewCase.getSubmissionId(), reviewCase.getStatus(),
                reviewCase.getNote(), reviewCase.getCreatedAt(), reviewCase.getDecidedAt());
    }
}
