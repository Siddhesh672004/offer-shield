package com.offershield.auth;

import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTTP clients Spring Security uses for the three sign-in calls to Google: the token endpoint, the userinfo
 * endpoint and the JWKS (ID-token signing keys). Spring's defaults have no timeouts, so a stuck Google would hold a
 * request thread forever; these share one request factory with a 5 s connect and 10 s read timeout. Each client is
 * configured exactly like Spring Security's default apart from the request factory.
 */
@Component
public class GoogleHttpClients {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final SimpleClientHttpRequestFactory requestFactory;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    private final OidcUserService oidcUserService;
    private final JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory;

    public GoogleHttpClients() {
        this(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    GoogleHttpClients(Duration connectTimeout, Duration readTimeout) {
        this.requestFactory = new SimpleClientHttpRequestFactory();
        this.requestFactory.setConnectTimeout(connectTimeout);
        this.requestFactory.setReadTimeout(readTimeout);
        this.tokenResponseClient = tokenResponseClient(requestFactory);
        this.oidcUserService = oidcUserService(requestFactory);
        this.idTokenDecoderFactory = new IdTokenDecoderFactory(new RestTemplate(requestFactory));
    }

    SimpleClientHttpRequestFactory requestFactory() {
        return requestFactory;
    }

    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient() {
        return tokenResponseClient;
    }

    OidcUserService oidcUserService() {
        return oidcUserService;
    }

    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        return idTokenDecoderFactory;
    }

    /** Same converters and error handler as {@code AbstractRestClientOAuth2AccessTokenResponseClient}'s default. */
    private static OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient(
            SimpleClientHttpRequestFactory requestFactory) {
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }

    /** Same error handler as {@code DefaultOAuth2UserService}'s default RestTemplate. */
    private static OidcUserService oidcUserService(SimpleClientHttpRequestFactory requestFactory) {
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultOAuth2UserService userInfoService = new DefaultOAuth2UserService();
        userInfoService.setRestOperations(restTemplate);
        OidcUserService oidcUserService = new OidcUserService();
        oidcUserService.setOauth2UserService(userInfoService);
        return oidcUserService;
    }

    /**
     * What {@link OidcIdTokenDecoderFactory} builds for an RS256 provider (Google), plus the JWKS RestTemplate, which
     * that factory can't take. One decoder per registration so Google's keys stay cached between sign-ins.
     */
    private static final class IdTokenDecoderFactory implements JwtDecoderFactory<ClientRegistration> {

        private final RestTemplate jwksClient;
        private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

        private IdTokenDecoderFactory(RestTemplate jwksClient) {
            this.jwksClient = jwksClient;
        }

        @Override
        public JwtDecoder createDecoder(ClientRegistration registration) {
            return decoders.computeIfAbsent(registration.getRegistrationId(), id -> build(registration));
        }

        private JwtDecoder build(ClientRegistration registration) {
            String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
            if (!StringUtils.hasText(jwkSetUri)) {
                OAuth2Error error = new OAuth2Error("missing_signature_verifier",
                        "Failed to find a Signature Verifier for Client Registration: '"
                                + registration.getRegistrationId() + "'. Check to ensure you have configured the JwkSet URI.",
                        null);
                throw new OAuth2AuthenticationException(error, error.toString());
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(jwksClient)
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(registration)));
            decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
            return decoder;
        }
    }
}
