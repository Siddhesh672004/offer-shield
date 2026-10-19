package com.offershield.auth;

import com.offershield.college.CollegeSummary;

import java.util.UUID;

/**
 * Body of GET /api/auth/me. For a USER, {@code college} is the college of their join request (any status) and
 * {@code membershipStatus} is NONE, PENDING, APPROVED or REJECTED. For a TPO or ADMIN, {@code college} is the college
 * they manage (if any) and {@code membershipStatus} is always NONE.
 */
public record MeResponse(UUID userId, String name, String email, Role role, CollegeSummary college, String membershipStatus) {
}
