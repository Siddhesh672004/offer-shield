-- One row per Google account, keyed on the immutable OIDC "sub" claim (D-02). Email is kept for display and role matching only.
CREATE TABLE app_user (
    id              BINARY(16)    NOT NULL,
    google_sub      VARCHAR(255)  NOT NULL,
    email           VARCHAR(320)  NOT NULL,
    email_verified  BOOLEAN       NOT NULL,
    name            VARCHAR(200)  NULL,
    role            VARCHAR(16)   NOT NULL DEFAULT 'USER',
    tpo_college_id  BINARY(16)    NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_google_sub UNIQUE (google_sub),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'TPO', 'ADMIN')),
    CONSTRAINT fk_app_user_tpo_college FOREIGN KEY (tpo_college_id) REFERENCES college (id),
    INDEX idx_app_user_email (email),
    INDEX idx_app_user_tpo_college (tpo_college_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
