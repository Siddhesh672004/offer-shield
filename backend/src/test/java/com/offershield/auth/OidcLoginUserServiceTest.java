package com.offershield.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OidcLoginUserServiceTest {

    @SuppressWarnings("unchecked")
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
    private final OidcUserProvisioningService provisioningService = mock(OidcUserProvisioningService.class);
    private final OidcLoginUserService service = new OidcLoginUserService(delegate, provisioningService);

    @Test
    void loadUser_provisionsAndReturnsAppPrincipal() {
        givenGoogleUser(Map.of("sub", "google-sub-1", "email", "sam@example.com", "email_verified", true, "name", "Sam Student"));
        UUID userId = UUID.randomUUID();
        when(provisioningService.provision(new OidcIdentity("google-sub-1", "sam@example.com", true, "Sam Student")))
                .thenReturn(new ProvisionedUser(userId, Role.TPO));

        OidcUser result = service.loadUser(null);

        assertThat(result).isInstanceOfSatisfying(AppOidcUser.class, user -> {
            assertThat(user.userId()).isEqualTo(userId);
            assertThat(user.role()).isEqualTo(Role.TPO);
            assertThat(user.getSubject()).isEqualTo("google-sub-1");
            assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_TPO");
        });
    }

    @Test
    void emailVerifiedClaimMissing_treatedAsUnverified() {
        givenGoogleUser(Map.of("sub", "google-sub-2", "email", "nov@example.com", "name", "No Verify"));
        when(provisioningService.provision(any())).thenReturn(new ProvisionedUser(UUID.randomUUID(), Role.USER));

        service.loadUser(null);

        verify(provisioningService).provision(new OidcIdentity("google-sub-2", "nov@example.com", false, "No Verify"));
    }

    private void givenGoogleUser(Map<String, Object> claims) {
        Map<String, Object> allClaims = new HashMap<>(claims);
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(300), allClaims);
        when(delegate.loadUser(any())).thenReturn(new DefaultOidcUser(List.of(), idToken));
    }
}
