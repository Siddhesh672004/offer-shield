package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-12 through the real security chain, not just the isolated filter. */
class RateLimitChainTest extends IntegrationTest {

    @Test
    void request61_sameUser_429Problem() throws Exception {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/api/ping").with(signedIn(userId, Role.USER))).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/ping").with(signedIn(userId, Role.USER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void otherUser_notAffected() throws Exception {
        UUID busyUser = UUID.randomUUID();
        for (int i = 0; i < 61; i++) {
            mockMvc.perform(get("/api/ping").with(signedIn(busyUser, Role.USER)));
        }

        mockMvc.perform(get("/api/ping").with(signedIn(UUID.randomUUID(), Role.USER))).andExpect(status().isOk());
    }
}
