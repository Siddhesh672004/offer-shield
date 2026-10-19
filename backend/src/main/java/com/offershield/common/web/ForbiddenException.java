package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/**
 * 403 decided by a service from database state (e.g. an unverified email, R-C). Never use it to hide another
 * user's data: that is a 404 (R-D).
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String errorCode, String detail) {
        super(HttpStatus.FORBIDDEN, errorCode, detail);
    }
}
