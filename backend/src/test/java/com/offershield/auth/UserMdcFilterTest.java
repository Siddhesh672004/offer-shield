package com.offershield.auth;

import com.offershield.common.logging.LogContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMdcFilterTest {

    private final UserMdcFilter filter = new UserMdcFilter();
    private final AtomicReference<String> seenUserId = new AtomicReference<>();
    private final FilterChain capturingChain = (request, response) -> seenUserId.set(MDC.get(LogContext.USER_ID));

    @AfterEach
    void clearState() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void signedIn_userIdInMdcDuringChain() throws Exception {
        UUID userId = UUID.randomUUID();
        signIn(userId);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), new MockHttpServletResponse(), capturingChain);

        assertThat(seenUserId.get()).isEqualTo(userId.toString());
    }

    @Test
    void afterChain_userIdRemoved() throws Exception {
        signIn(UUID.randomUUID());

        filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), new MockHttpServletResponse(), capturingChain);

        assertThat(seenUserId.get()).isNotNull();
        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void chainThrows_userIdStillRemoved() {
        signIn(UUID.randomUUID());
        FilterChain failingChain = (request, response) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/me"), new MockHttpServletResponse(), failingChain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void anonymous_noUserId() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/ping"), new MockHttpServletResponse(), capturingChain);

        assertThat(seenUserId.get()).isNull();
        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    private static void signIn(UUID userId) {
        AppOidcUser principal = new AppOidcUser(userId, Role.USER,
                new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(300), Map.of("sub", "sub-" + userId)),
                null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
