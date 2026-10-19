package com.offershield.common.web;

import org.springframework.http.HttpStatus;

public class UnprocessableException extends ApiException {

    public UnprocessableException(String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "UNPROCESSABLE", detail);
    }
}
