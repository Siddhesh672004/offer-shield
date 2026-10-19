-- Admin-created "this email becomes TPO of this college" grants; consumed (deleted) at the first verified sign-in (R-C).
-- email is stored trimmed and lower-case.
CREATE TABLE tpo_assignment (
    id           BINARY(16)    NOT NULL,
    email        VARCHAR(320)  NOT NULL,
    college_id   BINARY(16)    NOT NULL,
    assigned_by  BINARY(16)    NOT NULL,
    created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_tpo_assignment_email UNIQUE (email),
    CONSTRAINT fk_tpo_assignment_college FOREIGN KEY (college_id) REFERENCES college (id),
    CONSTRAINT fk_tpo_assignment_assigned_by FOREIGN KEY (assigned_by) REFERENCES app_user (id),
    INDEX idx_tpo_assignment_college (college_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
