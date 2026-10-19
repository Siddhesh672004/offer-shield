package com.offershield.auth;

/** The verified claims we use from a Google ID token. */
public record OidcIdentity(String sub, String email, boolean emailVerified, String name) {
}
