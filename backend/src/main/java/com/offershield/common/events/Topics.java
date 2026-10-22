package com.offershield.common.events;

/** Kafka topic names (D-20). Each topic has a dead-letter topic named {@code <topic>.DLT}, set explicitly (V-05). */
public final class Topics {

    public static final String SUBMISSION_CREATED = "submission.created";
    public static final String ANALYSIS_COMPLETED = "analysis.completed";
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
