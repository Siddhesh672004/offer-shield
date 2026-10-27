package com.offershield.submission;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.college.CollegeRepository;
import com.offershield.common.events.EventHeaders;
import com.offershield.common.events.Topics;
import com.offershield.membership.MembershipRepository;
import com.offershield.support.IntegrationTest;
import com.offershield.support.LogCapture;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRD done-when for P5 against the embedded KRaft broker: an upload returns 202 and its {@code submission.created}
 * event is on the topic, keyed by submission ID, with a deterministic event ID and the request ID header, and with
 * no letter text, email, phone or UPI ID in it (also not in any log line: S-11 for this flow).
 */
class SubmissionCreatedEventIT extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;

    @Test
    void upload_202_eventOnTopic_withoutLetterTextOrPii() throws Exception {
        SubmissionFixtures fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        UUID user = fixtures.user();
        String requestId;
        String submissionId;
        try (LogCapture logs = LogCapture.root()) {
            var response = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey())
                            .param("text", SubmissionFixtures.LETTER)
                            .param("senderEmail", SubmissionFixtures.SENDER)
                            .param("recruiterPhone", SubmissionFixtures.PHONE)
                            .param("upiId", SubmissionFixtures.UPI))
                    .andExpect(status().isAccepted())
                    .andExpect(header().exists("X-Request-Id"))
                    .andReturn().getResponse();
            requestId = response.getHeader("X-Request-Id");
            submissionId = JsonPath.read(response.getContentAsString(), "$.submissionId");

            assertNoPii(logs.events());
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(line -> assertThat(line).startsWith("event published topic=submission.created"));
        }

        ConsumerRecord<String, String> record = readOne(Topics.SUBMISSION_CREATED, submissionId);
        String json = record.value();
        assertThat(json).doesNotContain(SubmissionFixtures.MARKER)
                .doesNotContain("Junior Analyst")
                .doesNotContain("fictional-hiring.example")
                .doesNotContain("99999")
                .doesNotContain(SubmissionFixtures.UPI)
                .doesNotContain(SubmissionFixtures.SENDER);

        UUID expectedEventId = UUID.nameUUIDFromBytes(("submission.created:" + submissionId).getBytes(StandardCharsets.UTF_8));
        assertThat(record.key()).isEqualTo(submissionId);
        assertThat(headerValue(record, EventHeaders.EVENT_ID)).isEqualTo(expectedEventId.toString());
        assertThat(headerValue(record, EventHeaders.EVENT_TYPE)).isEqualTo("submission.created");
        assertThat(headerValue(record, EventHeaders.REQUEST_ID)).isEqualTo(requestId);
        assertThat(headerValue(record, "__TypeId__")).isEqualTo("com.offershield.common.events.SubmissionCreatedEvent");

        Map<String, Object> event = JsonPath.read(json, "$");
        assertThat(event).containsOnlyKeys("eventId", "eventType", "schemaVersion", "occurredAt", "requestId", "payload");
        assertThat(event.get("eventId")).isEqualTo(expectedEventId.toString());
        assertThat(event.get("schemaVersion")).isEqualTo(1);
        assertThat((String) event.get("occurredAt")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        Map<String, Object> payload = JsonPath.read(json, "$.payload");
        assertThat(payload).containsOnlyKeys("submissionId", "ownerUserId", "collegeId", "sourceType", "contentSha256");
        assertThat(payload.get("submissionId")).isEqualTo(submissionId);
        assertThat(payload.get("ownerUserId")).isEqualTo(user.toString());
        assertThat(payload.get("collegeId")).isNull();
        assertThat(payload.get("sourceType")).isEqualTo("TEXT");
        assertThat((String) payload.get("contentSha256")).matches("[0-9a-f]{64}");
    }

    @Test
    void topicsAndDeadLetterTopics_createdWithThreePartitionsAndRetention() {
        Set<String> names = Set.of("submission.created", "submission.created.DLT", "analysis.completed", "analysis.completed.DLT");

        Map<String, TopicDescription> topics = KAFKA.doWithAdminFunction(admin -> {
            try {
                return admin.describeTopics(names).allTopicNames().get();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(topics).containsOnlyKeys(names);
        topics.values().forEach(topic -> assertThat(topic.partitions()).hasSize(3));

        Map<ConfigResource, Config> configs = KAFKA.doWithAdminFunction(admin -> {
            try {
                return admin.describeConfigs(names.stream()
                        .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name)).toList()).all().get();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        configs.forEach((resource, config) -> {
            long retention = Long.parseLong(config.get(TopicConfig.RETENTION_MS_CONFIG).value());
            Duration expected = resource.name().endsWith(".DLT") ? Duration.ofDays(7) : Duration.ofDays(3);
            assertThat(retention).as(resource.name()).isEqualTo(expected.toMillis());
        });
    }

    static ConsumerRecord<String, String> readOne(String topic, String key) {
        List<ConsumerRecord<String, String>> records = readAll(topic, key, 1);
        assertThat(records).as("record for key " + key).isNotEmpty();
        return records.get(records.size() - 1);
    }

    /** Every record of {@code topic} with {@code key}, waiting up to 15 s for at least {@code atLeast} of them. */
    static List<ConsumerRecord<String, String>> readAll(String topic, String key, int atLeast) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBrokersAsString(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
                if (found.size() >= atLeast && caughtUp(consumer, partitions)) {
                    break;
                }
            }
        }
        return found;
    }

    private static boolean caughtUp(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions) {
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
        return partitions.stream().allMatch(partition -> consumer.position(partition) >= ends.get(partition));
    }

    static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void assertNoPii(List<ILoggingEvent> events) {
        assertThat(events).isNotEmpty().allSatisfy(event -> {
            String line = event.getFormattedMessage() + " " + event.getMDCPropertyMap();
            assertThat(line).doesNotContain(SubmissionFixtures.MARKER)
                    .doesNotContain("99999")
                    .doesNotContain(SubmissionFixtures.UPI)
                    .doesNotContain(SubmissionFixtures.SENDER)
                    .doesNotContain("fictional-hiring.example");
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertThat(String.valueOf(proxy.getMessage())).doesNotContain(SubmissionFixtures.MARKER);
            }
        });
    }
}
