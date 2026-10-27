package com.offershield.submission;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.CollegeRepository;
import com.offershield.common.events.EventHeaders;
import com.offershield.common.events.Topics;
import com.offershield.membership.MembershipRepository;
import com.offershield.support.IntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-28 sweeper on MySQL 8.4 and the embedded broker: re-publish with the same event ID, then FAILED at the cap. */
class QueuedSubmissionSweeperIT extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private QueuedSubmissionSweeper sweeper;

    private SubmissionFixtures fixtures;
    private UUID user;

    @BeforeEach
    void setUp() {
        fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        user = fixtures.user();
    }

    @Test
    void staleQueuedRow_republishedWithSameEventId_thenFailedAtCap() throws Exception {
        UUID id = submit();
        Instant now = Instant.now();
        setPublishState(id, 0, now.minus(Duration.ofMinutes(3)));

        sweeper.sweep(now);

        Map<String, Object> row = row(id);
        assertThat(row.get("publish_attempts")).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(((LocalDateTime) row.get("last_published_at")).toInstant(ZoneOffset.UTC))
                .isCloseTo(now, org.assertj.core.api.Assertions.within(Duration.ofSeconds(1)));
        List<ConsumerRecord<String, String>> records = SubmissionCreatedEventIT.readAll(Topics.SUBMISSION_CREATED,
                id.toString(), 2);
        assertThat(records).hasSize(2);
        String expectedEventId = com.offershield.common.events.EventIds.of(Topics.SUBMISSION_CREATED, id).toString();
        assertThat(records).extracting(record -> SubmissionCreatedEventIT.headerValue(record, EventHeaders.EVENT_ID))
                .containsExactly(expectedEventId, expectedEventId);

        // Not due yet: one re-publish done 3 minutes ago waits 4 minutes.
        setPublishState(id, 1, now.minus(Duration.ofMinutes(3)));
        sweeper.sweep(now);
        assertThat(row(id).get("publish_attempts")).isEqualTo(1);

        // Four re-publishes done and the last one more than 16 minutes ago: give up.
        setPublishState(id, QueuedSubmissionSweeper.MAX_REPUBLISHES, now.minus(Duration.ofMinutes(17)));
        sweeper.sweep(now);

        assertThat(row(id).get("status")).isEqualTo("FAILED");
        mockMvc.perform(get("/api/submissions/{id}", id).with(signedIn(user, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void analysedRow_neverTouched() throws Exception {
        UUID id = submit();
        Instant now = Instant.now();
        setPublishState(id, 0, now.minus(Duration.ofMinutes(30)));
        jdbc.update("UPDATE submission SET status = 'READY' WHERE id = ?", SubmissionFixtures.idBytes(id));

        sweeper.sweep(now);

        assertThat(row(id).get("publish_attempts")).isEqualTo(0);
        assertThat(row(id).get("status")).isEqualTo("READY");
    }

    @Test
    void compareAndSet_secondUpdateWithStaleAttemptsChangesNothing(@Autowired SubmissionRepository repository,
                                                                    @Autowired org.springframework.transaction.PlatformTransactionManager tm) throws Exception {
        UUID id = submit();
        var tx = new org.springframework.transaction.support.TransactionTemplate(tm);

        Integer first = tx.execute(status -> repository.recordRepublish(id, 0, Instant.now()));
        Integer stale = tx.execute(status -> repository.recordRepublish(id, 0, Instant.now()));
        Integer staleFail = tx.execute(status -> repository.markFailed(id, 0));
        assertThat(first).isEqualTo(1);
        assertThat(stale).isZero();
        assertThat(staleFail).isZero();
        assertThat(row(id).get("publish_attempts")).isEqualTo(1);
    }

    private UUID submit() throws Exception {
        String body = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", SubmissionFixtures.LETTER))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }

    private void setPublishState(UUID id, int attempts, Instant lastPublishedAt) {
        jdbc.update("UPDATE submission SET publish_attempts = ?, last_published_at = ? WHERE id = ?", attempts,
                LocalDateTime.ofInstant(lastPublishedAt, ZoneOffset.UTC), SubmissionFixtures.idBytes(id));
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, publish_attempts, last_published_at FROM submission WHERE id = ?",
                SubmissionFixtures.idBytes(id));
    }
}
