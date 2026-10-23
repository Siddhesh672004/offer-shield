-- The risk report of one submission (PRD §7, §10; D-28). id = the submission's id, so one submission can never get
-- two reports. score = summed points of triggered checks, capped at 100; band LOW/MEDIUM/HIGH (PRD §7.2);
-- hard_override = forced High by C1/C10/C11; partial = a check (from P7 also the AI) was unavailable (D-31).
-- ai_used and explanation ("Explain simply" cache) are filled from P7. sender_domain feeds the registry snapshot (P9).
-- Owner reads are scoped by owner_user_id (PRD §14.3); retention (P11) deletes the row and its signals at day 30.
CREATE TABLE report (
    id              BINARY(16)    NOT NULL,
    owner_user_id   BINARY(16)    NOT NULL,
    college_id      BINARY(16)    NULL,
    score           INT           NOT NULL,
    band            VARCHAR(8)    NOT NULL,
    hard_override   BOOLEAN       NOT NULL,
    partial         BOOLEAN       NOT NULL,
    ai_used         BOOLEAN       NOT NULL DEFAULT FALSE,
    explanation     TEXT          NULL,
    sender_domain   VARCHAR(253)  NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_report_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_report_band CHECK (band IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT fk_report_submission FOREIGN KEY (id) REFERENCES submission (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
