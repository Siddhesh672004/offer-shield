package com.offershield.support;

import com.offershield.auth.AppOidcUser;
import com.offershield.auth.Role;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

public final class TestPrincipals {

    private TestPrincipals() {
    }

    public static AppOidcUser appUser(UUID userId, Role role) {
        OidcIdToken idToken = new OidcIdToken("test-token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "sub-" + userId));
        return new AppOidcUser(userId, role, idToken, null);
    }

    public static RequestPostProcessor signedIn(UUID userId, Role role) {
        AppOidcUser principal = appUser(userId, role);
        return authentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
    }

    public static RequestPostProcessor signedIn(Role role) {
        return signedIn(UUID.randomUUID(), role);
    }
}
