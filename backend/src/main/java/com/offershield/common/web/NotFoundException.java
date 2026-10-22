package com.offershield.common.web;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String detail) {
        this("NOT_FOUND", detail);
    }

    /**
     * A 404 whose {@code errorCode} names why (e.g. {@code PDF_NOT_AVAILABLE}). Only for something the caller is allowed
     * to know exists; anything that belongs to someone else stays a plain {@code NOT_FOUND} (PRD §14.3).
     */
    public NotFoundException(String errorCode, String detail) {
        super(HttpStatus.NOT_FOUND, errorCode, detail);
    }
}
