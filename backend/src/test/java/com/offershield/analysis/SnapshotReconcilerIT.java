package com.offershield.analysis;

import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.Score;
import com.offershield.common.Ids;
import com.offershield.common.events.EventIds;
import com.offershield.common.events.Topics;
import com.offershield.registry.ReportSnapshotListener;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import com.offershield.support.KafkaRecords;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P13 (silent failure 1): a finished report whose registry snapshot never arrived (the {@code registry-snapshot}
 * consumer failed and the record went to the DLT, or lagged) would break "Ask my TPO" and "Report scam" for good. The
 * reconciler re-publishes {@code analysis.completed} for reports older than 2 minutes (and younger than a day) that
 * have no snapshot, with the same deterministic event ID, so the consumer stores the snapshot once and ignores
 * repeats. Fake data only.
 */
class SnapshotReconcilerIT extends IntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private SnapshotReconciler reconciler;
    @Autowired
    private ReportSnapshotService snapshots;
    @Autowired
    private ReportService reports;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private CaseFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new CaseFixtures(context, mockMvc);
        registry.getListenerContainer(ReportSnapshotListener.ID).start();
    }

    @AfterEach
    void stopConsumer() {
        registry.getListenerContainer(ReportSnapshotListener.ID).stop();
    }

    @Test
    void reportWithoutSnapshot_republishedOnceStored() throws Exception {
        UUID owner = fixtures.user();
        UUID missing = fixtures.analysedWithoutSnapshot(owner, null);
        age(missing, Duration.ofMinutes(3));

        int republished = reconciler.reconcile(Instant.now());

        assertThat(republished).isGreaterThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> snapshots.find(missing).isPresent());
        List<ConsumerRecord<String, byte[]>> sent = KafkaRecords.readAll(Topics.ANALYSIS_COMPLETED,
                missing.toString(), 1, Duration.ofSeconds(15));
        assertThat(new String(sent.get(0).headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                .as("the deterministic event ID, so a repeat is a duplicate for every consumer")
                .isEqualTo(EventIds.of(Topics.ANALYSIS_COMPLETED, missing).toString());

        // Stored now: the next run leaves it alone.
        assertThat(reconciler.candidates(Instant.now())).doesNotContain(missing);
    }

    @Test
    void recentOrOldOrStored_leftAlone() throws Exception {
        UUID owner = fixtures.user();
        UUID fresh = fixtures.analysedWithoutSnapshot(owner, null);
        UUID old = fixtures.analysedWithoutSnapshot(owner, null);
        age(old, Duration.ofDays(2));
        UUID stored = fixtures.analysed(owner, null, null, List.of(CaseFixtures.key()), null);
        reports.save(UUID.randomUUID(), new NewReport(stored, owner, null, null, new Score(0, Band.LOW, false), false,
                List.of(CheckResult.passed(CheckCode.C1, "No money request.", 1))));
        age(stored, Duration.ofMinutes(3));

        List<UUID> candidates = reconciler.candidates(Instant.now());

        assertThat(candidates).doesNotContain(fresh, old, stored);
    }

    private void age(UUID submission, Duration by) {
        jdbc.update("UPDATE report SET created_at = created_at - INTERVAL ? SECOND WHERE id = ?", by.toSeconds(),
                Ids.toBytes(submission));
    }
}
