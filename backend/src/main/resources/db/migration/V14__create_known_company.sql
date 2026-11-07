-- The admin-only global list of genuine companies (PRD §4 F8, D-24; the PRD's per-college college_id is removed).
-- C3 and C4 compare a letter's sender domain with these companies' official domains (known_company_domain, V15).
-- Names are unique ignoring case and accents (the column collation). No seed rows: the admin adds each company after
-- checking its official domains on the company's own website (V-21).
CREATE TABLE known_company (
    id          BINARY(16)    NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_known_company_name UNIQUE (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
