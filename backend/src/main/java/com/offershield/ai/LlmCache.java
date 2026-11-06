package com.offershield.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * {@code llm_cache} (PRD §8.5, D-06; V12), owned by the {@code ai} feature: a valid answer stored under
 * sha256(task + prompt version + masked text), still masked, with the provider and model that wrote it. Rows are
 * never updated: a row that no longer parses is deleted ({@link #evict}, P13) and the next valid answer is stored in
 * its place; the retention job (P11) deletes rows older than 30 days. A database problem is not an AI problem:
 * it is logged by class only and treated as a miss (the call is made, nothing is stored).
 */
@Component
public class LlmCache {

    private static final Logger log = LoggerFactory.getLogger(LlmCache.class);

    private final JdbcTemplate jdbc;

    public LlmCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String cacheKey) {
        try {
            List<String> rows = jdbc.queryForList("SELECT response_json FROM llm_cache WHERE cache_key = ?",
                    String.class, cacheKey);
            return rows.stream().findFirst();
        } catch (DataAccessException ex) {
            log.warn("llm cache read failed error={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 30-day retention (D-06): deletes up to {@code limit} rows written before {@code cutoff} (index
     * {@code idx_llm_cache_created_at}). Unlike reads and writes, a database error is thrown: the retention job logs it.
     *
     * @return how many rows were deleted (fewer than {@code limit} means none are left)
     */
    public int deleteCreatedBefore(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM llm_cache WHERE created_at < ? LIMIT ?",
                LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC), limit);
    }

    /**
     * Removes a stored answer that no longer parses (P13), so the next valid answer can be stored under its key. A
     * database problem is logged by class only.
     */
    public void evict(String cacheKey) {
        try {
            jdbc.update("DELETE FROM llm_cache WHERE cache_key = ?", cacheKey);
        } catch (DataAccessException ex) {
            log.warn("llm cache delete failed error={}", ex.getClass().getSimpleName());
        }
    }

    /** INSERT IGNORE: when two analyses of the same letter race, the first stored answer stays. */
    public void put(String cacheKey, String task, String responseJson, String provider, String model) {
        try {
            jdbc.update("INSERT IGNORE INTO llm_cache (cache_key, task, response_json, provider, model) "
                    + "VALUES (?, ?, ?, ?, ?)", cacheKey, task, responseJson, provider, model);
        } catch (DataAccessException ex) {
            log.warn("llm cache write failed error={}", ex.getClass().getSimpleName());
        }
    }
}
