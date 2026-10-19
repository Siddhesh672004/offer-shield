package com.offershield.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The three sign-in calls to Google (token endpoint, userinfo, JWKS) must give up instead of hanging. A local socket
 * that accepts connections but never answers stands in for a stuck Google; the clients here use a 300 ms read timeout.
 */
class GoogleHttpClientsTest {

    private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration GIVE_UP_AFTER = Duration.ofSeconds(8);
    private static final String REDIRECT_URI = "http://localhost/login/oauth2/code/google";

    private static ServerSocket silentServer;

    private final GoogleHttpClients clients = new GoogleHttpClients(Duration.ofSeconds(1), SHORT_READ_TIMEOUT);

    @BeforeAll
    static void startSilentServer() throws IOException {
        silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    @AfterAll
    static void stopSilentServer() throws IOException {
        silentServer.close();
    }

    @Test
    void requestFactory_productionDefaults_5sConnect10sRead() {
        SimpleClientHttpRequestFactory factory = new GoogleHttpClients().requestFactory();

        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(5_000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(10_000);
    }

    @Test
    void tokenResponseClient_tokenEndpointNeverAnswers_failsWithReadTimeout() {
        ClientRegistration registration = registration();
        OAuth2AuthorizationCodeGrantRequest grant = new OAuth2AuthorizationCodeGrantRequest(registration, exchange());

        Throwable thrown = callWithin(() -> clients.tokenResponseClient().getTokenResponse(grant));

        assertReadTimedOut(thrown);
    }

    @Test
    void oidcUserService_userInfoEndpointNeverAnswers_failsWithReadTimeout() {
        OidcUserRequest request = new OidcUserRequest(registration(),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "fake-access-token", Instant.now(),
                        Instant.now().plusSeconds(300)),
                new OidcIdToken("fake-id-token", Instant.now(), Instant.now().plusSeconds(300), Map.of("sub", "fake-sub")));

        Throwable thrown = callWithin(() -> clients.oidcUserService().loadUser(request));

        assertReadTimedOut(thrown);
    }

    @Test
    void idTokenDecoder_jwksEndpointNeverAnswers_failsWithReadTimeout() {
        String unsignedJwt = base64Url("{\"alg\":\"RS256\",\"kid\":\"test-key\"}") + "."
                + base64Url("{\"sub\":\"fake-sub\"}") + "." + base64Url("fake-signature");

        Throwable thrown = callWithin(() -> clients.idTokenDecoderFactory().createDecoder(registration()).decode(unsignedJwt));

        assertReadTimedOut(thrown);
    }

    /** One decoder per registration keeps Google's signing keys cached between sign-ins instead of refetching them. */
    @Test
    void idTokenDecoderFactory_sameRegistration_reusesDecoder() {
        ClientRegistration registration = registration();

        assertThat(clients.idTokenDecoderFactory().createDecoder(registration))
                .isSameAs(clients.idTokenDecoderFactory().createDecoder(registration));
    }

    private static Throwable callWithin(Executable call) {
        return catchThrowable(() -> assertTimeoutPreemptively(GIVE_UP_AFTER, call));
    }

    private static void assertReadTimedOut(Throwable thrown) {
        assertThat(thrown).isNotNull();
        assertThat(Stream.iterate(thrown, Objects::nonNull, Throwable::getCause))
                .as("cause chain of %s", thrown)
                .anyMatch(SocketTimeoutException.class::isInstance);
    }

    private static ClientRegistration registration() {
        String base = "http://127.0.0.1:" + silentServer.getLocalPort();
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("openid", "email", "profile")
                .authorizationUri(base + "/auth")
                .tokenUri(base + "/token")
                .userInfoUri(base + "/userinfo")
                .userNameAttributeName("sub")
                .jwkSetUri(base + "/jwks")
                .build();
    }

    private static OAuth2AuthorizationExchange exchange() {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("http://127.0.0.1:" + silentServer.getLocalPort() + "/auth")
                .clientId("test-client")
                .redirectUri(REDIRECT_URI)
                .state("test-state")
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("fake-code")
                .state("test-state")
                .redirectUri(REDIRECT_URI)
                .build();
        return new OAuth2AuthorizationExchange(request, response);
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
