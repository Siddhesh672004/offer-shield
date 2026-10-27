package com.offershield.submission;

import com.offershield.common.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the 30-day retention job (P11, D-06; PRD §6.3, §9.1, §14.6) may do to submissions: find the expired rows it
 * still has work on, delete a private PDF from Cloudinary, and wipe rows. A wiped row keeps its ID, owner, college,
 * hashes, idempotency key and dates (review cases, scam reports and registry reporters refer to it), but loses the
 * encrypted letter text and contact details and the sender domain, and its status becomes DELETED. The PDF's public
 * ID is cleared only once Cloudinary confirmed the delete, so a failed delete is retried by the next run.
 * Plain SQL, like the registry: the job never loads the encrypted text into memory.
 */
@Component
public class SubmissionRetention {

    private static final Logger log = LoggerFactory.getLogger(SubmissionRetention.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final CloudinaryStore store;

    public SubmissionRetention(NamedParameterJdbcTemplate jdbc, CloudinaryStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /**
     * Expired rows (expires_at before {@code now}) that are not wiped yet or still have a PDF to delete, in ID order
     * after {@code after} (keyset paging, so a row whose PDF delete keeps failing is visited once per run).
     */
    public List<ExpiredSubmission> findExpired(Instant now, UUID after, int limit) {
        return jdbc.query("""
                        SELECT id, cloudinary_public_id FROM submission
                        WHERE expires_at < :now AND id > :after
                          AND (status <> 'DELETED' OR cloudinary_public_id IS NOT NULL)
                        ORDER BY id LIMIT :limit""",
                new MapSqlParameterSource("now", utc(now)).addValue("after", Ids.toBytes(after))
                        .addValue("limit", limit),
                (rs, rowNum) -> new ExpiredSubmission(Ids.fromBytes(rs.getBytes("id")),
                        rs.getString("cloudinary_public_id")));
    }

    /**
     * Deletes the private PDF; call it outside any database transaction (it can take up to the Cloudinary timeout).
     *
     * @return {@code true} when the asset is gone (deleted now or already missing); {@code false} when the delete
     *         failed or storage is not configured, so the public ID is kept and the next run tries again
     */
    public boolean deletePdf(String publicId) {
        if (!store.isConfigured()) {
            log.warn("pdf delete skipped: storage not configured");
            return false;
        }
        try {
            store.delete(publicId);
            return true;
        } catch (RuntimeException ex) {
            log.warn("pdf delete failed: {} (retried by the next retention run)", ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Wipes the expired rows among {@code ids} and forgets the PDFs of {@code pdfsGone}, inside the caller's
     * transaction (together with the deletion of their reports, so no row is ever half-wiped).
     *
     * @return how many rows became DELETED now (rows wiped by an earlier run are not counted again)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int wipe(Collection<UUID> ids, Collection<UUID> pdfsGone, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        int wiped = jdbc.update("""
                        UPDATE submission
                        SET status = 'DELETED', text_cipher = NULL, contacts_cipher = NULL, sender_domain = NULL
                        WHERE id IN (:ids) AND expires_at < :now AND status <> 'DELETED'""",
                new MapSqlParameterSource("ids", bytes(ids)).addValue("now", utc(now)));
        if (!pdfsGone.isEmpty()) {
            jdbc.update("UPDATE submission SET cloudinary_public_id = NULL WHERE id IN (:ids) AND status = 'DELETED'",
                    new MapSqlParameterSource("ids", bytes(pdfsGone)));
        }
        return wiped;
    }

    /** The IDs among {@code ids} whose 30 days are over (expires_at before {@code now}). */
    public Set<UUID> expiredAmong(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query("SELECT id FROM submission WHERE id IN (:ids) AND expires_at < :now",
                new MapSqlParameterSource("ids", bytes(ids)).addValue("now", utc(now)),
                (rs, rowNum) -> Ids.fromBytes(rs.getBytes("id"))));
    }

    private static List<byte[]> bytes(Collection<UUID> ids) {
        return ids.stream().map(Ids::toBytes).toList();
    }

    /** DATETIME columns hold UTC (Hibernate {@code jdbc.time_zone: UTC}, MySQL session time zone UTC). */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
