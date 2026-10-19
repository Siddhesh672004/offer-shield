package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that map to an RFC 9457 problem response. The message becomes the
 * problem's {@code detail}, so it must be safe to show to the caller (no personal data).
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected ApiException(HttpStatus status, String errorCode, String detail) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
