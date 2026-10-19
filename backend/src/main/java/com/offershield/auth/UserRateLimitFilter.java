package com.offershield.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.offershield.common.logging.LogContext;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Limits each signed-in user to N requests per minute (in memory, Bucket4j). Anonymous requests are
 * not limited here (no per-IP limit, D-12). Registered only inside the Spring Security chain, so it
 * is deliberately not a {@code @Component}.
 */
public class UserRateLimitFilter extends OncePerRequestFilter {

    static final String ERROR_CODE = "RATE_LIMITED";
    private static final long MAX_TRACKED_USERS = 100_000;
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final long requestsPerMinute;
    private final Cache<UUID, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(IDLE_EXPIRY)
            .maximumSize(MAX_TRACKED_USERS)
            .build();

    public UserRateLimitFilter(long requestsPerMinute) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute must be at least 1");
        }
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var currentUser = CurrentUser.from(SecurityContextHolder.getContext().getAuthentication());
        if (currentUser.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.get(currentUser.get().id(), id -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(request, response, probe.getNanosToWaitForRefill());
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute)
                        .refillIntervally(requestsPerMinute, Duration.ofMinutes(1)))
                .build();
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long nanosToWait)
            throws IOException {
        long retryAfterSeconds = Math.max(1, Duration.ofNanos(nanosToWait).plusNanos(999_999_999L).toSeconds());

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Too Many Requests");
        problem.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        problem.put("detail", "Too many requests. Please wait a minute and try again.");
        problem.put("instance", request.getRequestURI());
        problem.put("errorCode", ERROR_CODE);
        String requestId = MDC.get(LogContext.REQUEST_ID);
        if (requestId != null) {
            problem.put("requestId", requestId);
        }
        byte[] body = JSON.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
