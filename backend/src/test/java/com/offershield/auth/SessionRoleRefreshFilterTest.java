package com.offershield.auth;

import com.offershield.support.TestPrincipals;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionRoleRefreshFilterTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AtomicLong nanos = new AtomicLong();
    private final RoleLookup roleLookup = new RoleLookup(users, TTL, nanos::get);
    private final SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
    private final SessionRoleRefreshFilter filter = new SessionRoleRefreshFilter(roleLookup, contextRepository);

    /** What the rest of the chain (authorization, controllers) saw on each request. */
    private final List<Authentication> seenByChain = new ArrayList<>();
    private final FilterChain chain = (request, response) ->
            seenByChain.add(SecurityContextHolder.getContext().getAuthentication());

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_roleUnchanged_principalKeptNothingSaved() throws Exception {
        OAuth2AuthenticationToken session = signIn(Role.USER);
        when(users.findRoleById(userId)).thenReturn(Optional.of(Role.USER));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seenByChain).containsExactly(session);
        verify(contextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void doFilter_promotedInDatabase_principalRebuiltWithSameTokensAndSavedToSession() throws Exception {
        OAuth2AuthenticationToken session = signIn(Role.USER);
        WebAuthenticationDetails details = new WebAuthenticationDetails("127.0.0.1", "session-id");
        session.setDetails(details);
        when(users.findRoleById(userId)).thenReturn(Optional.of(Role.TPO));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        OAuth2AuthenticationToken refreshed = (OAuth2AuthenticationToken) seenByChain.getFirst();
        AppOidcUser before = (AppOidcUser) session.getPrincipal();
        AppOidcUser after = (AppOidcUser) refreshed.getPrincipal();
        assertThat(after.role()).isEqualTo(Role.TPO);
        assertThat(after.userId()).isEqualTo(userId);
        assertThat(after.getIdToken()).isSameAs(before.getIdToken());
        assertThat(refreshed.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_TPO");
        assertThat(refreshed.getAuthorizedClientRegistrationId()).isEqualTo("google");
        assertThat(refreshed.isAuthenticated()).isTrue();
        assertThat(refreshed.getDetails()).isSameAs(details);
        SecurityContext saved = SecurityContextHolder.getContext();
        assertThat(saved.getAuthentication()).isSameAs(refreshed);
        verify(contextRepository).saveContext(saved, request, response);
    }

    @Test
    void doFilter_demotedInDatabase_lowerRoleSaved() throws Exception {
        signIn(Role.ADMIN);
        when(users.findRoleById(userId)).thenReturn(Optional.of(Role.USER));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(((AppOidcUser) seenByChain.getFirst().getPrincipal()).role()).isEqualTo(Role.USER);
        assertThat(seenByChain.getFirst().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        verify(contextRepository).saveContext(any(), any(), any());
    }

    @Test
    void doFilter_requestsWithinTtl_oneDatabaseReadThenReadAgainAfterTtl() throws Exception {
        signIn(Role.USER);
        when(users.findRoleById(userId)).thenReturn(Optional.of(Role.USER));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        nanos.addAndGet(TTL.minusSeconds(1).toNanos());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        verify(users, times(1)).findRoleById(userId);

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        verify(users, times(2)).findRoleById(userId);
    }

    @Test
    void doFilter_anonymous_noLookup() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seenByChain).containsExactly((Authentication) null);
        verifyNoInteractions(users, contextRepository);
    }

    @Test
    void doFilter_userRowGone_principalKept() throws Exception {
        OAuth2AuthenticationToken session = signIn(Role.TPO);
        when(users.findRoleById(userId)).thenReturn(Optional.empty());

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seenByChain).containsExactly(session);
        verifyNoInteractions(contextRepository);
    }

    private OAuth2AuthenticationToken signIn(Role role) {
        AppOidcUser principal = TestPrincipals.appUser(userId, role);
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        return token;
    }
}
