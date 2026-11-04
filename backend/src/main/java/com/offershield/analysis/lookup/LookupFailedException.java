package com.offershield.analysis.lookup;

/**
 * An RDAP or DNS lookup that gave no usable answer (timeout, error status, bad body). Its message is a short reason
 * of our own ("timeout", "status 503", an exception class name), never a domain name or response content (D-27).
 */
class LookupFailedException extends Exception {

    LookupFailedException(String reason) {
        super(reason, null, false, false);
    }
}
