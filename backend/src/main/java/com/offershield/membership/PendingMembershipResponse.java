package com.offershield.membership;

import com.offershield.auth.UserContact;

import java.time.Instant;
import java.util.UUID;

/** One row of a TPO's queue. {@code requestedAt} is when the current request was made (a re-apply resets it). */
public record PendingMembershipResponse(UUID id, String studentName, String studentEmail, String rollNo,
                                        String department, Instant requestedAt, long version) {

    static PendingMembershipResponse from(Membership membership, UserContact student) {
        return new PendingMembershipResponse(membership.getId(), student.name(), student.email(),
                membership.getRollNo(), membership.getDepartment(), membership.getUpdatedAt(), membership.getVersion());
    }
}
