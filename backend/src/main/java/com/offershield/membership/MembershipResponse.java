package com.offershield.membership;

import java.util.UUID;

/** A student's own view of their request; {@code version} is what a TPO decision sends back as If-Match. */
public record MembershipResponse(UUID id, UUID collegeId, String collegeName, String rollNo, String department,
                                 MembershipStatus status, String reason, long version) {

    static MembershipResponse from(Membership membership, String collegeName) {
        return new MembershipResponse(membership.getId(), membership.getCollegeId(), collegeName,
                membership.getRollNo(), membership.getDepartment(), membership.getStatus(), membership.getReason(),
                membership.getVersion());
    }
}
