package com.offershield.college;

import com.offershield.auth.TpoAssignmentResult;
import com.offershield.auth.TpoAssignmentStatus;

/** {@code ASSIGNED}: the user was promoted now. {@code PENDING}: promoted at that email's first verified sign-in. */
public record AssignTpoResponse(String email, TpoAssignmentStatus status) {

    static AssignTpoResponse from(TpoAssignmentResult result) {
        return new AssignTpoResponse(result.email(), result.status());
    }
}
