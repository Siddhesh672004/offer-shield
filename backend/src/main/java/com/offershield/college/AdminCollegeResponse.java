package com.offershield.college;

import com.offershield.auth.CollegeTpoEmails;

import java.util.List;
import java.util.UUID;

/** One row of the admin college list: the college plus its TPOs and the emails still waiting for a first sign-in. */
public record AdminCollegeResponse(UUID id, String name, String city, CollegeStatus status,
                                   List<String> tpoEmails, List<String> pendingTpoEmails) {

    static AdminCollegeResponse from(College college, CollegeTpoEmails emails) {
        return new AdminCollegeResponse(college.getId(), college.getName(), college.getCity(), college.getStatus(),
                emails.tpoEmails(), emails.pendingTpoEmails());
    }
}
