package com.offershield.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract of {@code submission.created} v1 (02 §5), keyed by submission ID. IDs and metadata only: the letter text
 * and the optional sender email, phone and UPI ID stay encrypted in the database, and the consumer loads them by ID.
 * Additive changes keep {@code schemaVersion}; a breaking change needs a new topic.
 */
public record SubmissionCreatedEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                     String requestId, Payload payload) {

    public static final String TYPE = Topics.SUBMISSION_CREATED;
    public static final int SCHEMA_VERSION = 1;

    /**
     * @param collegeId     the owner's college when the submission was made on-campus, else {@code null}
     * @param sourceType    {@code PDF} or {@code TEXT}
     * @param contentSha256 SHA-256 (hex) of the extracted or pasted text
     */
    public record Payload(UUID submissionId, UUID ownerUserId, UUID collegeId, String sourceType, String contentSha256) {
    }

    public static SubmissionCreatedEvent of(Payload payload, Instant occurredAt, String requestId) {
        return new SubmissionCreatedEvent(EventIds.of(TYPE, payload.submissionId()), TYPE, SCHEMA_VERSION, occurredAt,
                requestId, payload);
    }
}
