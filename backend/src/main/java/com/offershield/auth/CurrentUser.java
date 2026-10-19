package com.offershield.auth;

import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/** The signed-in user as resolved from the Spring Security session. */
public record CurrentUser(UUID id, Role role) {

    public static Optional<CurrentUser> from(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppOidcUser user) {
            return Optional.of(new CurrentUser(user.userId(), user.role()));
        }
        return Optional.empty();
    }
}
