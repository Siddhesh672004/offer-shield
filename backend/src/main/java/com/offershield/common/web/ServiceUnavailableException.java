package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/** 503: a dependency (e.g. file storage) or a bounded worker pool is unavailable; the client may retry. */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(String errorCode, String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, errorCode, detail);
    }
}
