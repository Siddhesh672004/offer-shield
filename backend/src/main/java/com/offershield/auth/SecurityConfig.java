package com.offershield.auth;

import com.offershield.common.web.ProblemResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Google sign-in with a server session (D-21), SPA CSRF, security headers (D-10) and the
 * 60 requests/min per-user limit (D-12). Identity only ever comes from the session principal.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    static final long REQUESTS_PER_MINUTE = 60;

    /**
     * Google's access token is kept in the user's HTTP session, so logout's session invalidation discards it. (Boot's
     * default keeps it in an app-wide in-memory map keyed by principal name, which outlives the session.)
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Spring Security picks this bean up for ID-token validation; its JWKS fetch has the Google timeouts. */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(GoogleHttpClients googleHttpClients) {
        return googleHttpClients.idTokenDecoderFactory();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OidcLoginUserService oidcLoginUserService,
                                            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                                            ProblemDetailAccessDeniedHandler accessDeniedHandler,
                                            ProblemResponseWriter problemResponseWriter,
                                            OAuth2AuthorizedClientRepository authorizedClientRepository,
                                            GoogleHttpClients googleHttpClients,
                                            RoleLookup roleLookup) throws Exception {
        RequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        // Spring Security's default, made explicit so the role-refresh filter saves to the same place sign-in does.
        SecurityContextRepository securityContextRepository = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());

        http
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/ping", "/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // D-24: the global known-company list is admin-only (the controller checks it again).
                        .requestMatchers("/api/known-companies/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2Login(login -> login
                        .authorizedClientRepository(authorizedClientRepository)
                        .tokenEndpoint(token -> token.accessTokenResponseClient(googleHttpClients.tokenResponseClient()))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcLoginUserService))
                        .defaultSuccessUrl("/app", true)
                        .failureUrl("/app?login=failed"))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .csrf(csrf -> csrf.spa())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(authenticationEntryPoint, api)
                        .defaultAccessDeniedHandlerFor(accessDeniedHandler, api))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny()))
                .addFilterBefore(new SessionRoleRefreshFilter(roleLookup, securityContextRepository),
                        AuthorizationFilter.class)
                .addFilterAfter(new UserMdcFilter(), AuthorizationFilter.class)
                .addFilterAfter(new UserRateLimitFilter(REQUESTS_PER_MINUTE, problemResponseWriter),
                        AuthorizationFilter.class);

        return http.build();
    }
}
