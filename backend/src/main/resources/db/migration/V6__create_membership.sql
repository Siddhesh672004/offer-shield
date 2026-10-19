-- A student's request to join a college (PRD §3.1 C, §4 F2). One row per student: re-applying after REJECTED
-- moves the same row back to PENDING, possibly for another college (D-33.3). Only that college's TPO decides.
CREATE TABLE membership (
    id          BINARY(16)    NOT NULL,
    user_id     BINARY(16)    NOT NULL,
    college_id  BINARY(16)    NOT NULL,
    roll_no     VARCHAR(50)   NOT NULL,
    department  VARCHAR(100)  NOT NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    reason      VARCHAR(500)  NULL,
    decided_by  BINARY(16)    NULL,
    decided_at  DATETIME(6)   NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_user UNIQUE (user_id),
    CONSTRAINT ck_membership_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_membership_college FOREIGN KEY (college_id) REFERENCES college (id),
    CONSTRAINT fk_membership_decided_by FOREIGN KEY (decided_by) REFERENCES app_user (id),
    INDEX idx_membership_college_status_created (college_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
