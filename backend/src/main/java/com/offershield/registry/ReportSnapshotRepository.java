package com.offershield.registry;

import com.offershield.common.Ids;
import com.offershield.common.events.AnalysisCompletedEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Plain SQL over {@code report_snapshot} (V17): one upsert by {@code submission_id} ({@code ON DUPLICATE KEY UPDATE},
 * so a replayed or newer completion simply overwrites the copy) and reads by ID. Lists, signals and (P13) the marker
 * hints are stored as JSON.
 */
@Repository
class ReportSnapshotRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<AnalysisCompletedEvent.Signal>> SIGNALS = new TypeReference<>() {
    };
    private static final TypeReference<List<AnalysisCompletedEvent.MarkerHint>> HINTS = new TypeReference<>() {
    };
    private static final RowMapper<ReportSnapshotView> VIEW = (rs, rowNum) -> new ReportSnapshotView(
            Ids.fromBytes(rs.getBytes("submission_id")), Ids.fromBytes(rs.getBytes("owner_user_id")),
            Ids.fromBytes(rs.getBytes("college_id")), rs.getString("band"), rs.getInt("score"),
            rs.getBoolean("hard_override"), rs.getBoolean("partial"), rs.getString("domain"),
            JSON.readValue(rs.getString("phone_hmacs"), STRINGS), JSON.readValue(rs.getString("upi_hmacs"), STRINGS),
            rs.getObject("simhash", Long.class), JSON.readValue(rs.getString("signals_json"), SIGNALS),
            rs.getBoolean("indicators_complete"),
            rs.getObject("completed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString("sender_source"),
            rs.getObject("letter_email_domains", Integer.class), hints(rs.getString("marker_hints")));

    private final NamedParameterJdbcTemplate jdbc;

    ReportSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void upsert(ReportSnapshotView snapshot) {
        jdbc.update("""
                INSERT INTO report_snapshot (submission_id, owner_user_id, college_id, band, score, hard_override,
                    partial, domain, sender_source, letter_email_domains, phone_hmacs, upi_hmacs, marker_hints, simhash,
                    signals_json, indicators_complete, completed_at)
                VALUES (:submission, :owner, :college, :band, :score, :hardOverride, :partial, :domain, :senderSource,
                    :letterEmailDomains, :phones, :upis, :hints, :simhash, :signals, :complete, :completedAt) AS incoming
                ON DUPLICATE KEY UPDATE owner_user_id = incoming.owner_user_id, college_id = incoming.college_id,
                    band = incoming.band, score = incoming.score, hard_override = incoming.hard_override,
                    partial = incoming.partial, domain = incoming.domain, sender_source = incoming.sender_source,
                    letter_email_domains = incoming.letter_email_domains, phone_hmacs = incoming.phone_hmacs,
                    upi_hmacs = incoming.upi_hmacs, marker_hints = incoming.marker_hints, simhash = incoming.simhash,
                    signals_json = incoming.signals_json,
                    indicators_complete = incoming.indicators_complete, completed_at = incoming.completed_at
                """, new MapSqlParameterSource("submission", Ids.toBytes(snapshot.submissionId()))
                .addValue("owner", Ids.toBytes(snapshot.ownerUserId()))
                .addValue("college", snapshot.collegeId() == null ? null : Ids.toBytes(snapshot.collegeId()))
                .addValue("band", snapshot.band()).addValue("score", snapshot.score())
                .addValue("hardOverride", snapshot.hardOverride()).addValue("partial", snapshot.partial())
                .addValue("domain", snapshot.domain())
                .addValue("senderSource", snapshot.senderSource())
                .addValue("letterEmailDomains", snapshot.letterEmailDomains())
                .addValue("hints", JSON.writeValueAsString(snapshot.markerHints()))
                .addValue("phones", JSON.writeValueAsString(snapshot.phoneHmacs()))
                .addValue("upis", JSON.writeValueAsString(snapshot.upiHmacs()))
                .addValue("simhash", snapshot.simhash())
                .addValue("signals", JSON.writeValueAsString(snapshot.signals()))
                .addValue("complete", snapshot.indicatorsComplete())
                .addValue("completedAt", LocalDateTime.ofInstant(snapshot.completedAt(), ZoneOffset.UTC)));
    }

    private static List<AnalysisCompletedEvent.MarkerHint> hints(String json) {
        return json == null || json.isBlank() ? List.of() : JSON.readValue(json, HINTS);
    }

    Optional<ReportSnapshotView> find(UUID submissionId) {
        return jdbc.query("SELECT * FROM report_snapshot WHERE submission_id = :submission",
                new MapSqlParameterSource("submission", Ids.toBytes(submissionId)), VIEW).stream().findFirst();
    }

    /** The IDs among {@code submissionIds} that have a snapshot (P13 reconciliation). */
    Set<UUID> existing(Collection<UUID> submissionIds) {
        return new HashSet<>(jdbc.query("SELECT submission_id FROM report_snapshot WHERE submission_id IN (:ids)",
                new MapSqlParameterSource("ids", submissionIds.stream().map(Ids::toBytes).toList()),
                (rs, rowNum) -> Ids.fromBytes(rs.getBytes("submission_id"))));
    }

    /** Snapshot keys after {@code after}, in key order (keyset paging for the retention job). */
    List<UUID> findSubmissionIds(UUID after, int limit) {
        return jdbc.query("SELECT submission_id FROM report_snapshot WHERE submission_id > :after "
                        + "ORDER BY submission_id LIMIT :limit",
                new MapSqlParameterSource("after", Ids.toBytes(after)).addValue("limit", limit),
                (rs, rowNum) -> Ids.fromBytes(rs.getBytes("submission_id")));
    }

    int delete(Collection<UUID> submissionIds) {
        return jdbc.update("DELETE FROM report_snapshot WHERE submission_id IN (:ids)",
                new MapSqlParameterSource("ids", submissionIds.stream().map(Ids::toBytes).toList()));
    }
}
