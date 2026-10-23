package com.offershield.common.kafka;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Consumer dedupe (PRD §10.1, §13.4, D-28): one {@code processed_event} row per (consumer group, event ID), written in
 * the same transaction as the consumer's work, so a redelivered event is recognised and its work is never done twice.
 */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event inside the caller's transaction.
     *
     * @return {@code false} when it was already recorded (a duplicate delivery): the caller skips its work
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        try {
            jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)", consumer, eventId.toString());
            return true;
        } catch (DuplicateKeyException ex) {
            // Already recorded: a redelivery. MySQL undoes only this statement, and the exception is handled here, so
            // the caller's transaction goes on. A concurrent insert of the same key waits for the other transaction
            // and then lands here too. Only a duplicate key means this (P13): INSERT IGNORE also turned a value that is
            // too long, or any other constraint failure, into a silent success.
            return false;
        }
    }

    /**
     * Retention (P11): deletes up to {@code limit} rows recorded before {@code cutoff} (index
     * {@code idx_processed_event_processed_at}). The job keeps 14 days, well beyond Kafka's 3 days (7 for DLTs, D-06),
     * so no redelivery can arrive for a deleted row.
     *
     * @return how many rows were deleted (fewer than {@code limit} means none are left)
     */
    public int deleteProcessedBefore(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM processed_event WHERE processed_at < ? LIMIT ?",
                LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC), limit);
    }

    @Transactional(readOnly = true)
    public boolean isProcessed(String consumer, UUID eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE consumer = ? AND event_id = ?", Integer.class,
                consumer, eventId.toString());
        return count != null && count > 0;
    }
}
