package com.offershield.common.web;

import org.springframework.http.HttpStatus;

import java.time.Duration;

/** 429 decided from database counts (D-12); {@code retryAfter} becomes the {@code Retry-After} header. */
public class TooManyRequestsException extends ApiException {

    private final Duration retryAfter;

    public TooManyRequestsException(String errorCode, String detail, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, errorCode, detail);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, at least 1. */
    public long retryAfterSeconds() {
        if (retryAfter == null) {
            return 1;
        }
        return Math.max(1, retryAfter.plusNanos(999_999_999L).toSeconds());
    }
}
