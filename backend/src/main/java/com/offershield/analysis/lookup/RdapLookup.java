package com.offershield.analysis.lookup;

import java.time.Instant;

/**
 * What RDAP said about a domain: its registration date ({@code FOUND}), an answer without one
 * ({@code NO_REGISTRATION_DATE}, e.g. some NIXI {@code .in} records, I-29), or no usable answer ({@code UNAVAILABLE}).
 */
public record RdapLookup(Status status, Instant registeredAt) {

    public enum Status {
        FOUND,
        NO_REGISTRATION_DATE,
        UNAVAILABLE
    }

    public static RdapLookup found(Instant registeredAt) {
        return new RdapLookup(Status.FOUND, registeredAt);
    }

    public static RdapLookup noRegistrationDate() {
        return new RdapLookup(Status.NO_REGISTRATION_DATE, null);
    }

    public static RdapLookup unavailable() {
        return new RdapLookup(Status.UNAVAILABLE, null);
    }
}
