package com.offershield.registry;

import com.offershield.common.Ids;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL over {@code indicator} and {@code indicator_reporter} (V18, V19): the registry's key operations are
 * race-free upserts ({@code ON DUPLICATE KEY UPDATE}), row locks ({@code FOR UPDATE}) and Hamming distance in the
 * database ({@code BIT_COUNT(simhash ^ ?)}, PRD §10.1), which read most clearly as SQL. Times are stored in UTC like
 * the rest of the schema. Callers own the transaction.
 */
@Repository
class IndicatorRepository {

    private static final String COLUMNS =
            "id, type, match_key, status, reporter_count, version, confirmed_by, confirmed_at";
    private static final List<String> LIVE = List.of(IndicatorStatus.UNVERIFIED.name(), IndicatorStatus.CONFIRMED.name());
    private static final RowMapper<IndicatorRow> ROW = (rs, rowNum) -> new IndicatorRow(
            Ids.fromBytes(rs.getBytes("id")), IndicatorType.valueOf(rs.getString("type")), rs.getString("match_key"),
            IndicatorStatus.valueOf(rs.getString("status")), rs.getInt("reporter_count"), rs.getLong("version"),
            Ids.fromBytes(rs.getBytes("confirmed_by")), utc(rs.getObject("confirmed_at", LocalDateTime.class)));

    private final NamedParameterJdbcTemplate jdbc;

    IndicatorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the marker with {@code status} unless a marker with the same type and key exists (then nothing). */
    void insertIfAbsent(MarkerCandidate marker, IndicatorStatus status) {
        jdbc.update("INSERT INTO indicator (id, type, match_key, simhash, status) "
                        + "VALUES (:id, :type, :key, :simhash, :status) ON DUPLICATE KEY UPDATE id = id",
                new MapSqlParameterSource().addValue("id", Ids.toBytes(Ids.newId()))
                        .addValue("type", marker.type().name()).addValue("key", marker.key())
                        .addValue("simhash", marker.simhash()).addValue("status", status.name()));
    }

    Optional<IndicatorRow> lockByKey(IndicatorType type, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM indicator WHERE type = :type AND match_key = :key FOR UPDATE",
                new MapSqlParameterSource("type", type.name()).addValue("key", key), ROW).stream().findFirst();
    }

    Optional<IndicatorRow> lockById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM indicator WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", Ids.toBytes(id)), ROW).stream().findFirst();
    }

    Optional<IndicatorRow> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM indicator WHERE id = :id",
                new MapSqlParameterSource("id", Ids.toBytes(id)), ROW).stream().findFirst();
    }

    Optional<IndicatorRow> findByKey(IndicatorType type, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM indicator WHERE type = :type AND match_key = :key",
                new MapSqlParameterSource("type", type.name()).addValue("key", key), ROW).stream().findFirst();
    }

    /** Writes a new state and bumps the version (the row must be locked by the caller). */
    void update(UUID id, IndicatorStatus status, int reporterCount, UUID confirmedBy, Instant confirmedAt) {
        jdbc.update("UPDATE indicator SET status = :status, reporter_count = :count, confirmed_by = :confirmedBy, "
                        + "confirmed_at = :confirmedAt, version = version + 1 WHERE id = :id",
                new MapSqlParameterSource("status", status.name()).addValue("count", reporterCount)
                        .addValue("confirmedBy", confirmedBy == null ? null : Ids.toBytes(confirmedBy))
                        .addValue("confirmedAt", confirmedAt == null ? null
                                : LocalDateTime.ofInstant(confirmedAt, ZoneOffset.UTC))
                        .addValue("id", Ids.toBytes(id)));
    }

    /** Adds one reporter of one submission to a marker; a repeat changes nothing. */
    void addReporter(UUID indicatorId, UUID userId, UUID submissionId) {
        jdbc.update("INSERT INTO indicator_reporter (indicator_id, user_id, submission_id) "
                        + "VALUES (:indicator, :user, :submission) ON DUPLICATE KEY UPDATE indicator_id = indicator_id",
                new MapSqlParameterSource("indicator", Ids.toBytes(indicatorId)).addValue("user", Ids.toBytes(userId))
                        .addValue("submission", Ids.toBytes(submissionId)));
    }

    /** The markers a user's report of one submission is attached to. */
    List<UUID> indicatorsReportedBy(UUID submissionId, UUID userId) {
        return jdbc.query("SELECT indicator_id FROM indicator_reporter WHERE submission_id = :submission "
                        + "AND user_id = :user ORDER BY indicator_id",
                new MapSqlParameterSource("submission", Ids.toBytes(submissionId)).addValue("user", Ids.toBytes(userId)),
                (rs, rowNum) -> Ids.fromBytes(rs.getBytes("indicator_id")));
    }

    void removeReporter(UUID submissionId, UUID userId) {
        jdbc.update("DELETE FROM indicator_reporter WHERE submission_id = :submission AND user_id = :user",
                new MapSqlParameterSource("submission", Ids.toBytes(submissionId)).addValue("user", Ids.toBytes(userId)));
    }

    /** Distinct people across the given markers (D-33.5): a user reporting several of them counts once. */
    int countDistinctReporters(Collection<UUID> indicatorIds) {
        if (indicatorIds.isEmpty()) {
            return 0;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM indicator_reporter "
                        + "WHERE indicator_id IN (:ids)",
                new MapSqlParameterSource("ids", indicatorIds.stream().map(Ids::toBytes).toList()), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Distinct reporters of one marker as committed right now (a locking read): inside a write transaction a plain
     * read would see the snapshot taken at its first read and could miss a reporter added meanwhile.
     */
    int countReportersLocked(UUID indicatorId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM indicator_reporter "
                + "WHERE indicator_id = :id FOR SHARE", new MapSqlParameterSource("id", Ids.toBytes(indicatorId)),
                Integer.class);
        return count == null ? 0 : count;
    }

    /** UNVERIFIED and CONFIRMED markers of {@code type} whose key is one of {@code keys}. */
    List<MatchedMarker> findLive(IndicatorType type, Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT id, type, match_key, status FROM indicator WHERE type = :type "
                        + "AND match_key IN (:keys) AND status IN (:live)",
                new MapSqlParameterSource("type", type.name()).addValue("keys", keys).addValue("live", LIVE),
                (rs, rowNum) -> new MatchedMarker(Ids.fromBytes(rs.getBytes("id")), type, IndicatorStatus.valueOf(
                        rs.getString("status")), rs.getString("match_key"), null));
    }

    /**
     * UNVERIFIED and CONFIRMED SimHash markers within {@code maxBits} differing bits of {@code simhash}, closest first.
     * A filtered scan of the SIMHASH rows (index on type, status): fine below about 100k markers (PRD §10.1).
     */
    List<MatchedMarker> findLiveNear(long simhash, int maxBits) {
        return jdbc.query("SELECT id, match_key, status, BIT_COUNT(simhash ^ :simhash) AS distance FROM indicator "
                        + "WHERE type = 'SIMHASH' AND status IN (:live) AND BIT_COUNT(simhash ^ :simhash) <= :maxBits "
                        + "ORDER BY distance",
                new MapSqlParameterSource("simhash", simhash).addValue("live", LIVE).addValue("maxBits", maxBits),
                (rs, rowNum) -> new MatchedMarker(Ids.fromBytes(rs.getBytes("id")), IndicatorType.SIMHASH,
                        IndicatorStatus.valueOf(rs.getString("status")), rs.getString("match_key"), rs.getInt("distance")));
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
