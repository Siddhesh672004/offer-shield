-- A user's report that one of their own analysed submissions is a scam (PRD §4 F6, §6.4, §10; D-12, D-33.4, D-33.7).
-- One report per submission and reporter (only the submission's owner may report it). The description is the
-- reporter's own words, often with phone numbers or UPI IDs, so it is stored only AES-256-GCM encrypted
-- (description_cipher = 12-byte IV + ciphertext + tag, AAD = "<id>:description", key_version as for submission text).
-- amount_asked = rupees the scammer asked for, if any. Status: OPEN → CONFIRMED / REJECTED by the admin with If-Match
-- on version (PRD §13.8); decided_by / decided_at record who and when (the audit_log row is written too, R-E).
-- The daily limit (5 per user per 24 h, D-12) is a count over (reporter_user_id, created_at).
CREATE TABLE scam_report (
    id                  BINARY(16)   NOT NULL,
    submission_id       BINARY(16)   NOT NULL,
    reporter_user_id    BINARY(16)   NOT NULL,
    description_cipher  BLOB         NOT NULL,
    key_version         INT          NOT NULL,
    amount_asked        INT          NULL,
    status              VARCHAR(12)  NOT NULL DEFAULT 'OPEN',
    decided_by          BINARY(16)   NULL,
    decided_at          DATETIME(6)  NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_scam_report_submission_reporter UNIQUE (submission_id, reporter_user_id),
    CONSTRAINT ck_scam_report_status CHECK (status IN ('OPEN', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT ck_scam_report_amount CHECK (amount_asked IS NULL OR amount_asked >= 0),
    CONSTRAINT fk_scam_report_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT fk_scam_report_reporter FOREIGN KEY (reporter_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_scam_report_decided_by FOREIGN KEY (decided_by) REFERENCES app_user (id),
    INDEX idx_scam_report_reporter_created (reporter_user_id, created_at),
    INDEX idx_scam_report_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
