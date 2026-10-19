-- Kafka consumer dedupe (D-28): insert in the same transaction as the work; a duplicate key means "already done".
CREATE TABLE processed_event (
    consumer      VARCHAR(64)  NOT NULL,
    event_id      VARCHAR(100) NOT NULL,
    processed_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer, event_id),
    INDEX idx_processed_event_processed_at (processed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
