-- AI answer cache of the ai feature (PRD §8.5, D-06). cache_key = sha256 hex of task + prompt version + masked text,
-- so the same letter is never sent to the provider twice. response_json is the validated answer, still masked
-- (placeholders, never raw contact details). Rows are never updated; the retention job (P11) deletes rows older than
-- 30 days by created_at.
CREATE TABLE llm_cache (
    cache_key       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task            VARCHAR(16)   NOT NULL,
    response_json   TEXT          NOT NULL,
    provider        VARCHAR(16)   NOT NULL,
    model           VARCHAR(100)  NOT NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (cache_key),
    INDEX idx_llm_cache_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
