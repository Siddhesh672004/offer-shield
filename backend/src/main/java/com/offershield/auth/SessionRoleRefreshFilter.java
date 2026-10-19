package com.offershield.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keeps the session principal's role in step with the database. Without it, a user promoted to TPO (or demoted) while
 * signed in would keep the old role until the session ends (up to 4 h), even though /api/auth/me already shows the new
 * one. Runs inside the Spring Security chain just before authorization, so URL rules and {@code @PreAuthorize} both see
 * the current role. When the role differs, the principal is rebuilt with the same Google tokens and saved back into
 * the session. Registered only in {@link SecurityConfig}, so it is deliberately not a {@code @Component}.
 */
public class SessionRoleRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionRoleRefreshFilter.class);

    private final RoleLookup roleLookup;
    private final SecurityContextRepository securityContextRepository;

    public SessionRoleRefreshFilter(RoleLookup roleLookup, SecurityContextRepository securityContextRepository) {
        this.roleLookup = roleLookup;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
        Authentication authentication = holder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken token && token.getPrincipal() instanceof AppOidcUser user) {
            roleLookup.findRole(user.userId())
                    .filter(current -> current != user.role())
                    .ifPresent(current -> replacePrincipal(holder, token, user, current, request, response));
        }
        chain.doFilter(request, response);
    }

    private void replacePrincipal(SecurityContextHolderStrategy holder, OAuth2AuthenticationToken token, AppOidcUser user,
                                  Role current, HttpServletRequest request, HttpServletResponse response) {
        AppOidcUser refreshed = new AppOidcUser(user.userId(), current, user.getIdToken(), user.getUserInfo());
        OAuth2AuthenticationToken updated = new OAuth2AuthenticationToken(refreshed, refreshed.getAuthorities(),
                token.getAuthorizedClientRegistrationId());
        updated.setDetails(token.getDetails());

        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(updated);
        holder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        log.info("Session role refreshed userId={} from={} to={}", user.userId(), user.role(), current);
    }
}
