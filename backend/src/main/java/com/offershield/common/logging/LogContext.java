package com.offershield.common.logging;

/** MDC keys and headers shared by every log line (D-27). Values are IDs only, never personal data. */
public final class LogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String SUBMISSION_ID = "submissionId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private LogContext() {
    }
}
