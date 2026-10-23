-- An uploaded or pasted offer letter (PRD §4 F3, §10; D-28). The letter text is AES-256-GCM encrypted (text_cipher =
-- 12-byte IV + ciphertext + tag, AAD = submission id); the optional sender email, phone and UPI ID are encrypted the
-- same way in contacts_cipher. A replayed Idempotency-Key returns the same row (D-33.2); request_sha256 detects a key
-- reused for different content. publish_attempts / last_published_at drive the sweeper's 2/4/8/16-minute backoff.
-- text_cipher is nullable because the 30-day retention job wipes it (D-06).
CREATE TABLE submission (
    id                    BINARY(16)    NOT NULL,
    owner_user_id         BINARY(16)    NOT NULL,
    college_id            BINARY(16)    NULL,
    idempotency_key       VARCHAR(100)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256        CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type           VARCHAR(8)    NOT NULL,
    content_sha256        CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    text_cipher           MEDIUMBLOB    NULL,
    contacts_cipher       VARBINARY(2048) NULL,
    key_version           INT           NOT NULL,
    sender_domain         VARCHAR(253)  NULL,
    cloudinary_public_id  VARCHAR(255)  NULL,
    status                VARCHAR(16)   NOT NULL DEFAULT 'QUEUED',
    publish_attempts      INT           NOT NULL DEFAULT 0,
    last_published_at     DATETIME(6)   NOT NULL,
    expires_at            DATETIME(6)   NOT NULL,
    created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_submission_owner_idempotency_key UNIQUE (owner_user_id, idempotency_key),
    CONSTRAINT ck_submission_status CHECK (status IN ('QUEUED', 'READY', 'PARTIAL', 'FAILED', 'DELETED')),
    CONSTRAINT ck_submission_source_type CHECK (source_type IN ('PDF', 'TEXT')),
    CONSTRAINT ck_submission_publish_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT fk_submission_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_submission_college FOREIGN KEY (college_id) REFERENCES college (id),
    INDEX idx_submission_owner_created (owner_user_id, created_at),
    INDEX idx_submission_status_last_published (status, last_published_at),
    INDEX idx_submission_expires_at (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
