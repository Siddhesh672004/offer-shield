package com.offershield.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.offershield.common.web.ProblemResponseWriter;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
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

    private final long requestsPerMinute;
    private final ProblemResponseWriter problemResponseWriter;
    private final Cache<UUID, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(IDLE_EXPIRY)
            .maximumSize(MAX_TRACKED_USERS)
            .build();

    public UserRateLimitFilter(long requestsPerMinute, ProblemResponseWriter problemResponseWriter) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute must be at least 1");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.problemResponseWriter = problemResponseWriter;
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
        long retryAfterSeconds = Math.max(1,
                Duration.ofNanos(probe.getNanosToWaitForRefill()).plusNanos(999_999_999L).toSeconds());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        problemResponseWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please wait a minute and try again.", ERROR_CODE);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute)
                        .refillIntervally(requestsPerMinute, Duration.ofMinutes(1)))
                .build();
    }
}
