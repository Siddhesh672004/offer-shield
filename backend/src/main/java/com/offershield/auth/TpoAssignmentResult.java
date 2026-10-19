package com.offershield.auth;

/** {@code email} is the normalised (trimmed, lower-case) address the assignment was made for. */
public record TpoAssignmentResult(String email, TpoAssignmentStatus status) {
}
