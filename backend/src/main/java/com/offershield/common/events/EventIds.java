package com.offershield.common.events;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic event IDs (D-28): a name-based UUID of topic + aggregate ID, so a re-published event keeps its ID and
 * consumers' {@code processed_event} rows recognise the duplicate.
 */
public final class EventIds {

    private EventIds() {
    }

    public static UUID of(String topic, UUID aggregateId) {
        return UUID.nameUUIDFromBytes((topic + ":" + aggregateId).getBytes(StandardCharsets.UTF_8));
    }
}
