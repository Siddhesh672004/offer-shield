package com.offershield.common.events;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code analysis.completed} v1 stays additive (02 §5): an event written before P9 (no registry keys) still reads, as
 * no keys and {@code indicatorsComplete = false}; a P9 event round-trips through the same Kafka (de)serializers the
 * application uses.
 */
class AnalysisCompletedEventTest {

    @Test
    void preP9Json_readsAsNoKeysIncomplete() {
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"analysis.completed\","
                + "\"schemaVersion\":1,\"occurredAt\":\"2026-10-20T10:00:05Z\",\"requestId\":null,\"payload\":{"
                + "\"submissionId\":\"" + UUID.randomUUID() + "\",\"ownerUserId\":\"" + UUID.randomUUID() + "\","
                + "\"collegeId\":null,\"outcome\":\"READY\",\"score\":25,\"band\":\"MEDIUM\",\"hardOverride\":false,"
                + "\"unavailableChecks\":[],\"signals\":[],\"senderDomain\":\"young-firm.com\"}}";

        try (JacksonJsonDeserializer<AnalysisCompletedEvent> deserializer =
                     new JacksonJsonDeserializer<>(AnalysisCompletedEvent.class)) {
            AnalysisCompletedEvent event = deserializer.deserialize("analysis.completed",
                    json.getBytes(StandardCharsets.UTF_8));

            assertThat(event.payload().phoneHmacs()).isNull();
            assertThat(event.payload().upiHmacs()).isNull();
            assertThat(event.payload().simhash()).isNull();
            assertThat(event.payload().indicatorsComplete()).isNull();
            assertThat(event.payload().keysComplete()).isFalse();
            assertThat(event.payload().score()).isEqualTo(25);
            assertThat(event.payload().senderSource()).isNull();
            assertThat(event.payload().letterEmailDomains()).isNull();
            assertThat(event.payload().markerHints()).isEmpty();
        }
    }

    @Test
    void p9Event_roundTrips() {
        AnalysisCompletedEvent event = AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(UUID.randomUUID(),
                UUID.randomUUID(), null, "READY", 0, "HIGH", true, List.of(), List.of(), null, List.of("a".repeat(64)),
                List.of(), Long.MIN_VALUE + 7, true), Instant.parse("2026-10-20T10:00:05Z"), null);

        try (JacksonJsonSerializer<AnalysisCompletedEvent> serializer = new JacksonJsonSerializer<>();
             JacksonJsonDeserializer<AnalysisCompletedEvent> deserializer =
                     new JacksonJsonDeserializer<>(AnalysisCompletedEvent.class)) {
            byte[] bytes = serializer.serialize("analysis.completed", event);

            assertThat(deserializer.deserialize("analysis.completed", bytes)).isEqualTo(event);
        }
    }

    @Test
    void p13Event_roundTrips() {
        String key = "a".repeat(64);
        AnalysisCompletedEvent event = AnalysisCompletedEvent.of(new AnalysisCompletedEvent.Payload(UUID.randomUUID(),
                UUID.randomUUID(), null, "READY", 0, "HIGH", true, List.of(), List.of(), "young-firm.com",
                List.of(key), List.of(), null, true, "LETTER", 2,
                List.of(new AnalysisCompletedEvent.MarkerHint("PHONE", key, "TYPED", "\u2022\u2022\u2022\u20221234"))),
                Instant.parse("2026-10-20T10:00:05Z"), null);

        try (JacksonJsonSerializer<AnalysisCompletedEvent> serializer = new JacksonJsonSerializer<>();
             JacksonJsonDeserializer<AnalysisCompletedEvent> deserializer =
                     new JacksonJsonDeserializer<>(AnalysisCompletedEvent.class)) {
            byte[] bytes = serializer.serialize("analysis.completed", event);

            assertThat(deserializer.deserialize("analysis.completed", bytes)).isEqualTo(event);
        }
    }
}
