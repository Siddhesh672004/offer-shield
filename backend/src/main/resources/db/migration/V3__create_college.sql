-- Colleges that TPOs belong to (PRD §7.1). Only an admin creates them (P3); status gates new memberships.
CREATE TABLE college (
    id          BINARY(16)    NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    city        VARCHAR(100)  NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_college_name UNIQUE (name),
    CONSTRAINT ck_college_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
