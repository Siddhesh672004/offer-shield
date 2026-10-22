package com.offershield.common.events;

/** Kafka header names every event carries (02 §5, D-27). Values are IDs only, never personal data. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String REQUEST_ID = "requestId";

    private EventHeaders() {
    }
}
