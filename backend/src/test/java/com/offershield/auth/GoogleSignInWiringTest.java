package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.ObservationAuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.util.ReflectionTestUtils.getField;

/** The real login chain uses the timeout-bounded Google clients (their timeouts are proven in GoogleHttpClientsTest). */
class GoogleSignInWiringTest extends IntegrationTest {

    @Autowired
    private FilterChainProxy filterChainProxy;
    @Autowired
    private GoogleHttpClients googleHttpClients;
    @Autowired
    private OidcLoginUserService oidcLoginUserService;

    @Test
    void oidcLoginProvider_usesTimeoutBoundedGoogleClients() {
        OidcAuthorizationCodeAuthenticationProvider provider = oidcLoginProvider();

        assertThat(getField(provider, "accessTokenResponseClient")).isSameAs(googleHttpClients.tokenResponseClient());
        assertThat(getField(provider, "jwtDecoderFactory")).isSameAs(googleHttpClients.idTokenDecoderFactory());
        assertThat(getField(provider, "userService")).isSameAs(oidcLoginUserService);
        assertThat(getField(oidcLoginUserService, "delegate")).isSameAs(googleHttpClients.oidcUserService());
    }

    private OidcAuthorizationCodeAuthenticationProvider oidcLoginProvider() {
        OAuth2LoginAuthenticationFilter loginFilter = filterChainProxy.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(OAuth2LoginAuthenticationFilter.class::isInstance)
                .map(OAuth2LoginAuthenticationFilter.class::cast)
                .findFirst().orElseThrow();
        Object manager = getField(loginFilter, "authenticationManager");
        if (manager instanceof ObservationAuthenticationManager observed) {
            manager = getField(observed, "delegate");
        }
        return ((ProviderManager) manager).getProviders().stream()
                .filter(OidcAuthorizationCodeAuthenticationProvider.class::isInstance)
                .map(OidcAuthorizationCodeAuthenticationProvider.class::cast)
                .findFirst().orElseThrow();
    }
}
