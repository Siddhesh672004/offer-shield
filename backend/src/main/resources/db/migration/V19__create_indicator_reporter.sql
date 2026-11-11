-- Who reported a scam marker (PRD §6.4, §10; D-33.4, D-33.5): one row per marker, reporting user and the submission
-- they reported, so "Reported as a scam by N users" counts distinct users (a user is counted once however many of
-- their letters share the marker) and an admin reject removes exactly that report's rows.
CREATE TABLE indicator_reporter (
    indicator_id   BINARY(16)   NOT NULL,
    user_id        BINARY(16)   NOT NULL,
    submission_id  BINARY(16)   NOT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (indicator_id, user_id, submission_id),
    CONSTRAINT fk_indicator_reporter_indicator FOREIGN KEY (indicator_id) REFERENCES indicator (id) ON DELETE CASCADE,
    CONSTRAINT fk_indicator_reporter_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_indicator_reporter_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    INDEX idx_indicator_reporter_submission_user (submission_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
