-- The registry's copy of a finished report (PRD §5.3, §10; D-04, D-20), written by the registry-snapshot consumer of
-- analysis.completed (upsert by submission_id). It is the evidence a TPO or the admin sees and the source of the scam
-- markers when a scam is reported or confirmed (P10). domain = the sender's registrable domain (only one that passed
-- the SSRF guard); phone_hmacs / upi_hmacs = JSON arrays of HMAC-SHA256 hex values (never raw values); simhash = the
-- 64-bit wording fingerprint (NULL for short letters); signals_json = the report's signals (evidence masked, at most
-- 200 characters each). Retention (P11, D-06) deletes it at day 30 unless an open case still needs it.
CREATE TABLE report_snapshot (
    submission_id        BINARY(16)    NOT NULL,
    owner_user_id        BINARY(16)    NOT NULL,
    college_id           BINARY(16)    NULL,
    band                 VARCHAR(8)    NOT NULL,
    score                INT           NOT NULL,
    hard_override        BOOLEAN       NOT NULL,
    partial              BOOLEAN       NOT NULL,
    domain               VARCHAR(253)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    phone_hmacs          JSON          NOT NULL,
    upi_hmacs            JSON          NOT NULL,
    simhash              BIGINT        NULL,
    signals_json         JSON          NOT NULL,
    indicators_complete  BOOLEAN       NOT NULL,
    completed_at         DATETIME(6)   NOT NULL,
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (submission_id),
    CONSTRAINT ck_report_snapshot_band CHECK (band IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_report_snapshot_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT fk_report_snapshot_submission FOREIGN KEY (submission_id) REFERENCES submission (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
