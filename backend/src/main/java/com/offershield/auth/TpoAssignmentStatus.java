package com.offershield.auth;

/** Outcome of an admin assigning a TPO: promoted now, or waiting for that email's first verified sign-in. */
public enum TpoAssignmentStatus {
    ASSIGNED,
    PENDING
}
