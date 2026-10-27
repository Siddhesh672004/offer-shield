package com.offershield.submission;

import java.time.Instant;
import java.util.UUID;

/**
 * The owner's view of one submission, polled by the frontend until {@code status} is READY, PARTIAL or FAILED
 * (D-14). Never contains the letter text or contact details.
 *
 * @param onCampus  made while the owner was an approved member of a college (so "Ask my TPO" can apply)
 * @param pdfStored the original PDF is kept privately for reviewers (false for pasted text, when storage is off, and
 *                  once the submission is DELETED)
 * @param expiresAt when the 30-day retention job deletes it (D-06)
 */
public record SubmissionResponse(UUID id, SubmissionStatus status, SourceType sourceType, boolean onCampus,
                                 boolean pdfStored, Instant createdAt, Instant updatedAt, Instant expiresAt) {

    static SubmissionResponse from(SubmissionView view) {
        return new SubmissionResponse(view.id(), view.status(), view.sourceType(), view.collegeId() != null,
                view.status() != SubmissionStatus.DELETED && view.cloudinaryPublicId() != null, view.createdAt(),
                view.updatedAt(), view.expiresAt());
    }
}
