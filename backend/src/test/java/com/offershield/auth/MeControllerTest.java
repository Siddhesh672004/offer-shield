package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeControllerTest extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioningService;

    @Test
    void me_signedIn_returnsProfileAndRole() throws Exception {
        ProvisionedUser user = provisioningService.provision(
                new OidcIdentity("sub-me-" + UUID.randomUUID(), "me.student@example.com", true, "Me Student"));

        mockMvc.perform(get("/api/auth/me").with(signedIn(user.userId(), user.role())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.userId().toString()))
                .andExpect(jsonPath("$.email").value("me.student@example.com"))
                .andExpect(jsonPath("$.name").value("Me Student"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.membershipStatus").value("NONE"));
    }

    @Test
    void me_sessionUserMissingInDb_404Problem() throws Exception {
        mockMvc.perform(get("/api/auth/me").with(signedIn(UUID.randomUUID(), Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
