-- Shared audit trail for every TPO/admin decision (R-E, PRD §14.6).
CREATE TABLE audit_log (
    id          BINARY(16)   NOT NULL,
    actor_id    BINARY(16)   NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target_id   BINARY(16)   NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_audit_log_actor_created (actor_id, created_at),
    INDEX idx_audit_log_target (target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
