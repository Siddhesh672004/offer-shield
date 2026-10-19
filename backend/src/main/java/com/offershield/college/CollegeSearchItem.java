package com.offershield.college;

import java.util.UUID;

/** One row of the "find your college" search shown to signed-in users. */
public record CollegeSearchItem(UUID id, String name, String city) {

    static CollegeSearchItem from(College college) {
        return new CollegeSearchItem(college.getId(), college.getName(), college.getCity());
    }
}
