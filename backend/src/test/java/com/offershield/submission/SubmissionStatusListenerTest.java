package com.offershield.submission;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.CollegeRepository;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.events.Topics;
import com.offershield.common.kafka.InvalidEventException;
import com.offershield.membership.MembershipRepository;
import com.offershield.support.IntegrationTest;
import com.offershield.support.KafkaRecords;
import com.offershield.support.LogCapture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6-07 / D-20 / D-28: the {@code submission-status} consumer of {@code analysis.completed} moves a submission from
 * QUEUED to READY or PARTIAL exactly once (dedupe in {@code processed_event}), and a malformed record goes to
 * {@code analysis.completed.DLT} without retries (real broker).
 */
class SubmissionStatusListenerTest extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubmissionStatusListener listener;
    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private SubmissionFixtures fixtures;
    private UUID user;
    private UUID submission;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        user = fixtures.user();
        String body = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", SubmissionFixtures.LETTER))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        submission = UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }

    @Test
    void queued_toReady() throws Exception {
        listener.onAnalysisCompleted(event(AnalysisCompletedEvent.READY));

        mockMvc.perform(get("/api/submissions/{id}", submission).with(signedIn(user, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void partial_toPartial() {
        listener.onAnalysisCompleted(event(AnalysisCompletedEvent.PARTIAL));

        assertThat(statusOf(submission)).isEqualTo("PARTIAL");
    }

    @Test
    void duplicate_ignored() {
        AnalysisCompletedEvent event = event(AnalysisCompletedEvent.READY);
        listener.onAnalysisCompleted(event);
        jdbc.update("UPDATE submission SET status = 'QUEUED' WHERE id = ?", SubmissionFixtures.idBytes(submission));

        listener.onAnalysisCompleted(event);

        assertThat(statusOf(submission)).isEqualTo("QUEUED"); // the second delivery changed nothing
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = 'submission-status' "
                + "AND event_id = ?", Integer.class, event.eventId().toString())).isEqualTo(1);
    }

    @Test
    void lateCompletionAfterSweeperGaveUp_toReady() {
        jdbc.update("UPDATE submission SET status = 'FAILED' WHERE id = ?", SubmissionFixtures.idBytes(submission));

        listener.onAnalysisCompleted(event(AnalysisCompletedEvent.READY));

        assertThat(statusOf(submission)).isEqualTo("READY");
    }

    @Test
    void deleted_untouched() {
        jdbc.update("UPDATE submission SET status = 'DELETED' WHERE id = ?", SubmissionFixtures.idBytes(submission));

        listener.onAnalysisCompleted(event(AnalysisCompletedEvent.READY));

        assertThat(statusOf(submission)).isEqualTo("DELETED");
    }

    @Test
    void unknownOutcome_invalidNothingWritten() {
        AnalysisCompletedEvent bad = event("DONE");

        assertThatThrownBy(() -> listener.onAnalysisCompleted(bad)).isInstanceOf(InvalidEventException.class);
        assertThat(statusOf(submission)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class,
                bad.eventId().toString())).isZero();
    }

    @Test
    void poison_toDlt() {
        String key = UUID.randomUUID().toString();
        String invalidKey = submission.toString();
        byte[] poison = "{\"eventId\": not-json ZEBRA-POISON-STATUS".getBytes(StandardCharsets.UTF_8);
        byte[] noIds = "{\"eventType\":\"analysis.completed\",\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        MessageListenerContainer container = registry.getListenerContainer(SubmissionStatusListener.ID);
        assertThat(container).isNotNull();
        try (LogCapture logs = LogCapture.root()) {
            container.start();
            KafkaRecords.sendRaw(Topics.ANALYSIS_COMPLETED, key, poison);
            KafkaRecords.sendRaw(Topics.ANALYSIS_COMPLETED, invalidKey, noIds);

            List<ConsumerRecord<String, byte[]>> dead = KafkaRecords.readAll(Topics.dlt(Topics.ANALYSIS_COMPLETED), key, 1,
                    Duration.ofSeconds(30));
            List<ConsumerRecord<String, byte[]>> invalid = KafkaRecords.readAll(Topics.dlt(Topics.ANALYSIS_COMPLETED),
                    invalidKey, 1, Duration.ofSeconds(30));

            // Not JSON: dead-lettered with its original bytes.
            assertThat(dead).hasSize(1);
            ConsumerRecord<String, byte[]> record = dead.get(0);
            assertThat(record.value()).isEqualTo(poison);
            assertThat(KafkaRecords.header(record, "kafka_dlt-exception-fqcn")).contains("DeserializationException");
            assertThat(KafkaRecords.header(record, "kafka_dlt-original-topic")).isEqualTo(Topics.ANALYSIS_COMPLETED);
            assertThat(KafkaRecords.header(record, "kafka_dlt-exception-message")).isNull();
            assertThat(KafkaRecords.header(record, "kafka_dlt-exception-stacktrace")).isNull();
            // JSON without IDs: the listener rejects it, no retries, dead-lettered as JSON.
            assertThat(invalid).hasSize(1);
            assertThat(KafkaRecords.header(invalid.get(0), "kafka_dlt-exception-cause-fqcn"))
                    .isEqualTo(InvalidEventException.class.getName());
            assertThat(KafkaRecords.value(invalid.get(0))).contains("\"eventType\":\"analysis.completed\"");
            assertThat(logs.events()).noneSatisfy(event -> assertThat(event.getFormattedMessage()
                    + String.valueOf(event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
                    .contains("ZEBRA-POISON-STATUS"));
            assertThat(logs.events()).filteredOn(event -> invalidKey.equals(event.getMDCPropertyMap().get("submissionId"))
                            && event.getFormattedMessage().contains("call=SubmissionStatusListener"))
                    .hasSize(1); // the invalid event reached the listener once: no retries
        } finally {
            container.stop();
        }
        assertThat(statusOf(submission)).isEqualTo("QUEUED");
    }

    private AnalysisCompletedEvent event(String outcome) {
        return AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(submission, user, null, outcome, 40,
                "MEDIUM", false, List.of(), List.of(), null, List.of(), List.of(), null, true), Instant.now(),
                "req-status");
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM submission WHERE id = ?", String.class, SubmissionFixtures.idBytes(id));
    }
}
