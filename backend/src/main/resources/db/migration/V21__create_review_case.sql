-- "Ask my TPO" (PRD §4 F5, §6.5, §10; D-04, D-05, D-08, D-33.7): one review case per submission, created by its owner
-- while an APPROVED member of the college the submission was made in (college_id comes from the database, never the
-- request). Status: OPEN → CONFIRMED_SCAM / CONFIRMED_GENUINE / INCONCLUSIVE, decided once by a TPO of that college
-- with If-Match on version (PRD §13.8; a second TPO with the old version gets 409); note = the TPO's note to the
-- student; reviewer_id / decided_at record who and when (the audit_log row is written too, R-E). The evidence shown to
-- the TPO is the registry's report snapshot (D-04), never the letter text.
CREATE TABLE review_case (
    id               BINARY(16)     NOT NULL,
    submission_id    BINARY(16)     NOT NULL,
    college_id       BINARY(16)     NOT NULL,
    student_user_id  BINARY(16)     NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    note             VARCHAR(1000)  NULL,
    reviewer_id      BINARY(16)     NULL,
    decided_at       DATETIME(6)    NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_review_case_submission UNIQUE (submission_id),
    CONSTRAINT ck_review_case_status
        CHECK (status IN ('OPEN', 'CONFIRMED_SCAM', 'CONFIRMED_GENUINE', 'INCONCLUSIVE')),
    CONSTRAINT fk_review_case_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT fk_review_case_college FOREIGN KEY (college_id) REFERENCES college (id),
    CONSTRAINT fk_review_case_student FOREIGN KEY (student_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_review_case_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user (id),
    INDEX idx_review_case_college_status_created (college_id, status, created_at),
    INDEX idx_review_case_student_created (student_user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
