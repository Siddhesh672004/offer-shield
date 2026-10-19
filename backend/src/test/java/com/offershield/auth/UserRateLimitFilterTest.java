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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UserRateLimitFilterTest {

    private static final int LIMIT = 60;

    private final UserRateLimitFilter filter = new UserRateLimitFilter(LIMIT);
    private final AtomicInteger chainCalls = new AtomicInteger();
    private final FilterChain countingChain = (request, response) -> chainCalls.incrementAndGet();

    @AfterEach
    void clearState() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void request61_429() throws Exception {
        signIn(UUID.randomUUID());
        MDC.put(LogContext.REQUEST_ID, "req-123");

        for (int i = 1; i <= LIMIT; i++) {
            MockHttpServletResponse response = send("/api/me");
            assertThat(response.getStatus()).as("request %d", i).isEqualTo(200);
        }
        assertThat(chainCalls.get()).isEqualTo(LIMIT);

        MockHttpServletResponse limited = send("/api/me");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentType()).startsWith("application/problem+json");
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(chainCalls.get()).as("chain not called when limited").isEqualTo(LIMIT);

        JsonNode body = JsonMapper.builder().build().readTree(limited.getContentAsString());
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo("Too Many Requests");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("detail").asString()).isEqualTo("Too many requests. Please wait a minute and try again.");
        assertThat(body.get("instance").asString()).isEqualTo("/api/me");
        assertThat(body.get("errorCode").asString()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("requestId").asString()).isEqualTo("req-123");
    }

    @Test
    void request61_noRequestId_bodyOmitsRequestId() throws Exception {
        signIn(UUID.randomUUID());
        for (int i = 0; i < LIMIT; i++) {
            send("/api/me");
        }

        MockHttpServletResponse limited = send("/api/me");

        JsonNode body = JsonMapper.builder().build().readTree(limited.getContentAsString());
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(body.has("requestId")).isFalse();
    }

    @Test
    void request61_instanceWithQuotes_stillValidJson() throws Exception {
        signIn(UUID.randomUUID());
        for (int i = 0; i < LIMIT; i++) {
            send("/api/me");
        }

        MockHttpServletResponse limited = send("/api/\"x\"\\y");

        JsonNode body = JsonMapper.builder().build().readTree(limited.getContentAsString());
        assertThat(body.get("instance").asString()).isEqualTo("/api/\"x\"\\y");
    }

    @Test
    void twoUsers_separateBuckets() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        signIn(userA);
        for (int i = 0; i < LIMIT; i++) {
            send("/api/me");
        }
        assertThat(send("/api/me").getStatus()).isEqualTo(429);

        signIn(userB);
        assertThat(send("/api/me").getStatus()).isEqualTo(200);
    }

    @Test
    void anonymous_notLimited() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(send("/api/ping").getStatus()).as("request %d", i + 1).isEqualTo(200);
        }

        assertThat(chainCalls.get()).isEqualTo(100);
    }

    private MockHttpServletResponse send(String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", uri), response, countingChain);
        return response;
    }

    private static void signIn(UUID userId) {
        AppOidcUser principal = new AppOidcUser(userId, Role.USER,
                new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(300), Map.of("sub", "sub-" + userId)),
                null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
