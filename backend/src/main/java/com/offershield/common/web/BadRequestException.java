package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/** 400 for a request that passes bean validation but breaks a rule; {@code errorCode} names the rule. */
public class BadRequestException extends ApiException {

    public BadRequestException(String errorCode, String detail) {
        super(HttpStatus.BAD_REQUEST, errorCode, detail);
    }
}
