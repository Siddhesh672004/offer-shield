package com.offershield.membership;

import com.offershield.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** 409: the student already has a PENDING or APPROVED request (one row per student; only REJECTED may re-apply). */
class MembershipExistsException extends ApiException {

    MembershipExistsException() {
        super(HttpStatus.CONFLICT, "MEMBERSHIP_EXISTS", "You already have a pending or approved college request.");
    }
}
