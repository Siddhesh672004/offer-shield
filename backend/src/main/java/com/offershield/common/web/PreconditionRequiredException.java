package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/** 428: a state-changing decision was sent without {@code If-Match} (RFC 6585 §3). */
public class PreconditionRequiredException extends ApiException {

    public PreconditionRequiredException(String detail) {
        super(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", detail);
    }
}
