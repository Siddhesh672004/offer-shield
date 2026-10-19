package com.offershield.auth;

import com.offershield.college.CollegeSummary;

import java.util.UUID;

/**
 * Body of GET /api/auth/me. {@code college} is the TPO's college, else the college of the user's join request (any
 * status); {@code membershipStatus} is NONE, PENDING, APPROVED or REJECTED.
 */
public record MeResponse(UUID userId, String name, String email, Role role, CollegeSummary college, String membershipStatus) {
}
