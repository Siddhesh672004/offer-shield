package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginFlowTest extends IntegrationTest {

    @Test
    void failedCallback_redirectsToSpaFailureRoute() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/google").param("code", "fake-code").param("state", "unknown-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app?login=failed"));
    }

    @Test
    void anonymousApiRequest_createsNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void logout_invalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/api/auth/logout").session(session).with(signedIn(Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }
}
