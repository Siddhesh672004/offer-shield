-- A scam marker in the shared registry (PRD §4 F7, §10; D-08, D-13, D-33.4). One row per (type, match_key):
-- DOMAIN = a registrable sender domain (never a free-mail or known company's official domain, D-08); PHONE / UPI =
-- HMAC-SHA256 hex of the normalised value with the server pepper (never the raw value, PRD §10.1); SIMHASH = the
-- 16-hex-digit fingerprint, its bits also in simhash for BIT_COUNT(simhash ^ ?) matching. Status: UNVERIFIED (user
-- reports; feeds only C12) → CONFIRMED (admin or TPO; C10/C11 hard override) → back to UNVERIFIED on an audited
-- Revert (D-13); REJECTED = no reporter left (stops counting). reporter_count = distinct reporters (D-33.5); version
-- guards Revert (If-Match). Indicators are kept when reports are deleted (D-06).
CREATE TABLE indicator (
    id              BINARY(16)    NOT NULL,
    type            VARCHAR(8)    NOT NULL,
    match_key       VARCHAR(253)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    simhash         BIGINT        NULL,
    status          VARCHAR(12)   NOT NULL,
    reporter_count  INT           NOT NULL DEFAULT 0,
    confirmed_by    BINARY(16)    NULL,
    confirmed_at    DATETIME(6)   NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_indicator_type_match_key UNIQUE (type, match_key),
    CONSTRAINT ck_indicator_type CHECK (type IN ('DOMAIN', 'PHONE', 'UPI', 'SIMHASH')),
    CONSTRAINT ck_indicator_status CHECK (status IN ('UNVERIFIED', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT ck_indicator_simhash CHECK ((type = 'SIMHASH') = (simhash IS NOT NULL)),
    CONSTRAINT ck_indicator_reporter_count CHECK (reporter_count >= 0),
    CONSTRAINT fk_indicator_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES app_user (id),
    INDEX idx_indicator_type_status (type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
