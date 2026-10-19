package com.offershield.membership;

import com.offershield.auth.AppUser;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.College;
import com.offershield.college.CollegeRepository;

import java.util.UUID;

/** Fake users and colleges for membership integration tests. Every name and address is unique and made up. */
final class MembershipFixtures {

    private final OidcUserProvisioningService provisioning;
    private final AppUserRepository users;
    private final CollegeRepository colleges;

    MembershipFixtures(OidcUserProvisioningService provisioning, AppUserRepository users, CollegeRepository colleges) {
        this.provisioning = provisioning;
        this.users = users;
        this.colleges = colleges;
    }

    UUID college(String label) {
        return colleges.saveAndFlush(College.create(label + " College " + UUID.randomUUID(), "Pune")).getId();
    }

    UUID student() {
        return student(true);
    }

    UUID student(boolean emailVerified) {
        String key = UUID.randomUUID().toString();
        return provisioning.provision(new OidcIdentity("sub-student-" + key, "student-" + key + "@example.com",
                emailVerified, "Test Student " + key.substring(0, 8))).userId();
    }

    /** A verified user promoted to TPO of {@code collegeId} directly in the database (test setup only). */
    UUID tpo(UUID collegeId) {
        String key = UUID.randomUUID().toString();
        UUID userId = provisioning.provision(new OidcIdentity("sub-tpo-" + key, "tpo-" + key + "@example.com",
                true, "Test TPO")).userId();
        AppUser user = users.findById(userId).orElseThrow();
        user.assignTpoCollege(collegeId);
        user.changeRole(Role.TPO);
        users.saveAndFlush(user);
        return userId;
    }

    String email(UUID userId) {
        return users.findById(userId).orElseThrow().getEmail();
    }

    String name(UUID userId) {
        return users.findById(userId).orElseThrow().getName();
    }
}
