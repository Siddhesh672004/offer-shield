package com.offershield.college;

import java.util.UUID;

/** A college as the admin just created it; {@code version} is its optimistic-lock version (0 when new). */
public record CollegeResponse(UUID id, String name, String city, CollegeStatus status, Long version) {

    static CollegeResponse from(College college) {
        return new CollegeResponse(college.getId(), college.getName(), college.getCity(), college.getStatus(),
                college.getVersion());
    }
}
