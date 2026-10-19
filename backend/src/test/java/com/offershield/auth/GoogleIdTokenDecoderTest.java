package com.offershield.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ID-token decoder we build ourselves (for the JWKS timeouts) must still validate like Spring's own: signature
 * against the provider's JWKS, issuer, audience and expiry. Tokens are signed with a throwaway RSA key whose public
 * half is served by a local JWKS stub; nothing leaves the machine.
 */
class GoogleIdTokenDecoderTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

    private static RSAKey signingKey;
    private static HttpServer jwksServer;

    private final GoogleHttpClients clients = new GoogleHttpClients();

    @BeforeAll
    static void startJwksServer() throws Exception {
        signingKey = rsaKey("test-key");
        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwksServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        jwksServer.start();
    }

    @AfterAll
    static void stopJwksServer() {
        jwksServer.stop(0);
    }

    @Test
    void decode_validIdToken_accepted() throws Exception {
        Jwt jwt = decoder().decode(sign(signingKey, validClaims().build()));

        assertThat(jwt.getSubject()).isEqualTo("fake-google-sub");
        assertThat(jwt.getAudience()).containsExactly(CLIENT_ID);
    }

    @Test
    void decode_wrongIssuer_rejected() throws Exception {
        String token = sign(signingKey, validClaims().issuer("https://attacker.example.com").build());

        assertRejectedFor(token, "iss");
    }

    @Test
    void decode_wrongAudience_rejected() throws Exception {
        String token = sign(signingKey, validClaims().audience("someone-elses-client").build());

        assertRejectedFor(token, "aud");
    }

    @Test
    void decode_expired_rejected() throws Exception {
        Instant past = Instant.now().minus(Duration.ofMinutes(20));
        String token = sign(signingKey, validClaims()
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plus(Duration.ofMinutes(10))))
                .build());

        assertRejectedFor(token, "exp");
    }

    @Test
    void decode_signedWithKeyNotInJwks_rejected() throws Exception {
        String token = sign(rsaKey("test-key"), validClaims().build());

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(BadJwtException.class);
    }

    private JwtDecoder decoder() {
        return clients.idTokenDecoderFactory().createDecoder(registration());
    }

    private void assertRejectedFor(String token, String claim) {
        assertThatThrownBy(() -> decoder().decode(token))
                .isInstanceOfSatisfying(JwtValidationException.class, ex -> assertThat(ex.getErrors())
                        .extracting(OAuth2Error::getDescription)
                        .anySatisfy(description -> assertThat(description).contains(claim)));
    }

    private static JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("fake-google-sub")
                .audience(CLIENT_ID)
                .issueTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("email", "fake.student@example.com")
                .claim("email_verified", true);
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey rsaKey(String keyId) throws JOSEException {
        return new RSAKeyGenerator(2048).keyID(keyId).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }

    private static ClientRegistration registration() {
        String base = "http://127.0.0.1:" + jwksServer.getAddress().getPort();
        return ClientRegistration.withRegistrationId("google")
                .clientId(CLIENT_ID)
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google")
                .scope("openid", "email", "profile")
                .authorizationUri(base + "/auth")
                .tokenUri(base + "/token")
                .userInfoUri(base + "/userinfo")
                .userNameAttributeName("sub")
                .jwkSetUri(base + "/jwks")
                .issuerUri(ISSUER)
                .build();
    }
}
