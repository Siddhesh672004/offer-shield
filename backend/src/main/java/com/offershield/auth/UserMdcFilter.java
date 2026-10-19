package com.offershield.auth;

import com.offershield.common.logging.LogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the signed-in user's ID (never email or name) into the logging context for the rest of the
 * request (D-27). Registered only inside the Spring Security chain, so it is deliberately not a
 * {@code @Component}.
 */
public class UserMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var currentUser = CurrentUser.from(SecurityContextHolder.getContext().getAuthentication());
        if (currentUser.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(LogContext.USER_ID, currentUser.get().id().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LogContext.USER_ID);
        }
    }
}
