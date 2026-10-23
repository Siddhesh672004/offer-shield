package com.offershield.common.web;

import org.springframework.http.HttpStatus;

/** 415: the uploaded bytes are not a type we accept (decided from magic bytes, never the file name). */
public class UnsupportedMediaTypeException extends ApiException {

    public UnsupportedMediaTypeException(String errorCode, String detail) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, errorCode, detail);
    }
}
