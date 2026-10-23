-- One signal of a report (PRD §7, §10): the check's outcome, points, plain-language explanation, evidence (at most
-- 200 characters, contact details masked) and source kind 1-5 (PRD §7 table); ai_extracted labels AI-derived values
-- (P7). One row per check per report. Named report_signal because SIGNAL is a reserved word in MySQL.
CREATE TABLE report_signal (
    id              BINARY(16)    NOT NULL,
    report_id       BINARY(16)    NOT NULL,
    check_code      VARCHAR(8)    NOT NULL,
    outcome         VARCHAR(16)   NOT NULL,
    points          INT           NOT NULL,
    hard_override   BOOLEAN       NOT NULL,
    explanation     VARCHAR(500)  NOT NULL,
    evidence        VARCHAR(200)  NULL,
    source_kind     INT           NOT NULL,
    ai_extracted    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_report_signal_report_check UNIQUE (report_id, check_code),
    CONSTRAINT ck_report_signal_outcome CHECK (outcome IN ('TRIGGERED', 'PASSED', 'UNAVAILABLE', 'NOT_APPLICABLE')),
    CONSTRAINT ck_report_signal_points CHECK (points BETWEEN 0 AND 100),
    CONSTRAINT ck_report_signal_source_kind CHECK (source_kind BETWEEN 1 AND 5),
    CONSTRAINT fk_report_signal_report FOREIGN KEY (report_id) REFERENCES report (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
