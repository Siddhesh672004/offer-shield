package com.offershield.common.web;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String detail) {
        this("CONFLICT", detail);
    }

    /** A 409 whose {@code errorCode} names the clash (e.g. {@code DOMAIN_TAKEN}), so the UI can say what to change. */
    public ConflictException(String errorCode, String detail) {
        super(HttpStatus.CONFLICT, errorCode, detail);
    }
}
