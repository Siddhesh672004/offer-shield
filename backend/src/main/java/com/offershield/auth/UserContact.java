package com.offershield.auth;

import java.util.UUID;

/** What other features may know about a user: enough to address an email or show a TPO who is asking. */
public record UserContact(UUID id, String email, String name, boolean emailVerified) {
}
