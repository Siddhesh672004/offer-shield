package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Google's access token lives only in the user's HTTP session, so logout (session invalidation) discards it. */
class AuthorizedClientStorageTest extends IntegrationTest {

    @Autowired
    private OAuth2AuthorizedClientRepository authorizedClientRepository;
    @Autowired
    private ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService;
    @Autowired
    private ClientRegistrationRepository clientRegistrations;
    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    void savedAuthorizedClient_visibleOnlyInSameSession_notInAppWideStore() {
        Authentication principal = new TestingAuthenticationToken("google-sub-storage", null, "ROLE_USER");
        assertThat(principal.isAuthenticated()).isTrue();
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(clientRegistrations.findByRegistrationId("google"),
                principal.getName(), new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "fake-access-token",
                Instant.now(), Instant.now().plusSeconds(300)));
        MockHttpServletRequest signedInRequest = new MockHttpServletRequest();

        authorizedClientRepository.saveAuthorizedClient(client, principal, signedInRequest, new MockHttpServletResponse());

        OAuth2AuthorizedClient sameSession = authorizedClientRepository.loadAuthorizedClient("google", principal, signedInRequest);
        assertThat(sameSession).isNotNull();
        OAuth2AuthorizedClient newSession =
                authorizedClientRepository.loadAuthorizedClient("google", principal, new MockHttpServletRequest());
        assertThat(newSession).isNull();
        OAuth2AuthorizedClientService service = authorizedClientService.getIfAvailable();
        if (service != null) {
            OAuth2AuthorizedClient inMemory = service.loadAuthorizedClient("google", principal.getName());
            assertThat(inMemory).isNull();
        }
    }

    @Test
    void oauth2LoginFilter_storesAuthorizedClientInHttpSession() {
        OAuth2LoginAuthenticationFilter loginFilter = filterChainProxy.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(OAuth2LoginAuthenticationFilter.class::isInstance)
                .map(OAuth2LoginAuthenticationFilter.class::cast)
                .findFirst().orElseThrow();

        assertThat(ReflectionTestUtils.getField(loginFilter, "authorizedClientRepository"))
                .isInstanceOf(HttpSessionOAuth2AuthorizedClientRepository.class);
    }
}
