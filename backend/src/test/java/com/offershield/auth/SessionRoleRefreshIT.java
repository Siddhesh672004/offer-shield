package com.offershield.auth;

import com.offershield.college.College;
import com.offershield.college.CollegeRepository;
import com.offershield.support.IntegrationTest;
import com.offershield.support.TestPrincipals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A role change made while the user is signed in reaches their existing session on the next request: the session
 * principal is rebuilt from the database role and saved back into the HTTP session (no re-login needed).
 */
class SessionRoleRefreshIT extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CollegeRepository colleges;

    @Test
    void promotedWhileSignedIn_pendingNowAllowed() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID college = colleges.saveAndFlush(College.create("Role Refresh College " + key, "Pune")).getId();
        UUID admin = provisionWithRole("sub-refresh-admin-" + key, "refresh.admin." + key + "@example.com", Role.ADMIN);
        String studentEmail = "refresh.student." + key + "@example.com";
        UUID student = provisioning.provision(new OidcIdentity("sub-refresh-student-" + key, studentEmail, true,
                "Test Student")).userId();
        MockHttpSession session = sessionFor(student, Role.USER);

        mockMvc.perform(get("/api/memberships/pending").session(session)).andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/colleges/{id}/tpos", college).with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + studentEmail + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        mockMvc.perform(get("/api/memberships/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(sessionPrincipal(session).role()).isEqualTo(Role.TPO);
        assertThat(sessionPrincipal(session).userId()).isEqualTo(student);
    }

    /** Removed from the admin list and signed in elsewhere: the old session loses ADMIN at once. */
    @Test
    void demotedWhileSignedIn_adminPagesNowForbidden() throws Exception {
        String key = UUID.randomUUID().toString();
        String sub = "sub-refresh-former-admin-" + key;
        String email = "refresh.former.admin." + key + "@example.com";
        UUID formerAdmin = provisionWithRole(sub, email, Role.ADMIN);
        MockHttpSession session = sessionFor(formerAdmin, Role.ADMIN);

        mockMvc.perform(get("/api/admin/colleges").session(session)).andExpect(status().isOk());

        // ADMIN_EMAILS doesn't list this address, so signing in again recomputes the role as USER.
        assertThat(provisioning.provision(new OidcIdentity(sub, email, true, "Test Person")).role()).isEqualTo(Role.USER);

        mockMvc.perform(get("/api/admin/colleges").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        assertThat(sessionPrincipal(session).role()).isEqualTo(Role.USER);
    }

    /** A provisioned (verified) user whose role is then set directly in the database (test setup only). */
    private UUID provisionWithRole(String sub, String email, Role role) {
        UUID userId = provisioning.provision(new OidcIdentity(sub, email, true, "Test Person")).userId();
        AppUser user = users.findById(userId).orElseThrow();
        user.changeRole(role);
        users.saveAndFlush(user);
        return userId;
    }

    /** What the browser's session holds after Google sign-in: the security context under Spring's session key. */
    private static MockHttpSession sessionFor(UUID userId, Role role) {
        AppOidcUser principal = TestPrincipals.appUser(userId, role);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private static AppOidcUser sessionPrincipal(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return (AppOidcUser) context.getAuthentication().getPrincipal();
    }
}
