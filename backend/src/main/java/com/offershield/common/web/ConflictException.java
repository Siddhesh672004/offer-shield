package com.offershield.common.web;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String detail) {
        super(HttpStatus.CONFLICT, "CONFLICT", detail);
    }
}
