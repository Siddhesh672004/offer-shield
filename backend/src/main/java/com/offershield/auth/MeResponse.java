package com.offershield.auth;

import com.offershield.college.CollegeSummary;

import java.util.UUID;

/** Body of GET /api/auth/me. {@code college} is the TPO's college (P2) or the approved membership's college (P3). */
public record MeResponse(UUID userId, String name, String email, Role role, CollegeSummary college, String membershipStatus) {
}
