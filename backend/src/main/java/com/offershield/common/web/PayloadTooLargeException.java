package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/** 413: the uploaded content is larger than allowed (e.g. a PDF over 5 MB, PRD §14.4). */
public class PayloadTooLargeException extends ApiException {

    public PayloadTooLargeException(String errorCode, String detail) {
        super(HttpStatus.CONTENT_TOO_LARGE, errorCode, detail);
    }
}
