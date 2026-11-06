package com.offershield.ai;

import java.time.Duration;

/**
 * What one model call came back with (PRD §8.4). {@code content} is the assistant message (only for {@code OK});
 * {@code restFor} is how long a rate-limited model rests; {@code reason} is a short, content-free word for the log
 * ("timeout", "status-503", "invalid-json", …). {@link #toString()} never shows the content.
 */
public record ProviderReply(Outcome outcome, int status, String content, Duration restFor, Usage usage, String reason) {

    public enum Outcome {
        /** 200 with an assistant message. */
        OK,
        /** 429: rest this model until the reset, then try the next one. */
        RATE_LIMITED,
        /** 401/403: the key is wrong; every model shares it, so stop the chain. */
        AUTH_FAILED,
        /** 404 or "model not found / decommissioned": rest this model for a long time, try the next one. */
        UNKNOWN_MODEL,
        /** 5xx, other 4xx, timeout, connection refused, unreadable answer: try the next model. */
        FAILED
    }

    /** Token counts reported by the provider (V-09); {@code -1} when not reported. */
    public record Usage(int promptTokens, int completionTokens, int reasoningTokens) {

        public static final Usage NONE = new Usage(-1, -1, -1);
    }

    static ProviderReply ok(String content, Usage usage) {
        return new ProviderReply(Outcome.OK, 200, content, null, usage, "ok");
    }

    static ProviderReply rateLimited(Duration restFor) {
        return new ProviderReply(Outcome.RATE_LIMITED, 429, null, restFor, Usage.NONE, "rate-limited");
    }

    static ProviderReply failed(Outcome outcome, int status, String reason) {
        return new ProviderReply(outcome, status, null, null, Usage.NONE, reason);
    }

    @Override
    public String toString() {
        return "ProviderReply[outcome=" + outcome + ", status=" + status + ", reason=" + reason + ", redacted]";
    }
}
