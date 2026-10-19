package com.offershield.common.db;

import com.offershield.common.Ids;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves V-25 / D-32: Flyway migrations apply on MySQL 8.4. */
class FlywayMigrationIT extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** Versions must be 1..N with no gaps; later phases append V6, V7, ... without editing this test. */
    @Test
    void migrations_appliedOnMysql84_tablesExist() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4");
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).startsWith("1", "2", "3", "4", "5", "6")
                .isEqualTo(IntStream.rangeClosed(1, versions.size()).mapToObj(Integer::toString).toList());
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class)).contains("audit_log", "processed_event", "college", "app_user", "tpo_assignment", "membership");
    }

    @Test
    void appUser_duplicateGoogleSub_rejected() {
        String insert = "INSERT INTO app_user (id, google_sub, email, email_verified, role) VALUES (?, ?, ?, ?, 'USER')";
        jdbc.update(insert, idBytes(), "sub-dup-1", "student@example.com", true);

        assertThatThrownBy(() -> jdbc.update(insert, idBytes(), "sub-dup-1", "other@example.com", true))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static byte[] idBytes() {
        UUID id = Ids.newId();
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    @Test
    void processedEvent_duplicateKey_rejected() {
        String insert = "INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (?, ?, NOW(6))";
        jdbc.update(insert, "analysis", "evt-dup-1");

        assertThatThrownBy(() -> jdbc.update(insert, "analysis", "evt-dup-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
