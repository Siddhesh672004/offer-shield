package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/** 422: the request is well-formed but its content breaks a rule; {@code errorCode} names the rule. */
public class UnprocessableException extends ApiException {

    public UnprocessableException(String detail) {
        this("UNPROCESSABLE", detail);
    }

    public UnprocessableException(String errorCode, String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, errorCode, detail);
    }
}
