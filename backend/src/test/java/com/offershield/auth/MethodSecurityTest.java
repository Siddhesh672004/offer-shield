package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(MethodSecurityTest.AdminOnlyController.class)
class MethodSecurityTest extends IntegrationTest {

    @Test
    void wrongRole_403Problem() throws Exception {
        mockMvc.perform(get("/api/test/admin-only").with(signedIn(Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void rightRole_200() throws Exception {
        mockMvc.perform(get("/api/test/admin-only").with(signedIn(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @RestController
    static class AdminOnlyController {

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/test/admin-only")
        public String adminOnly() {
            return "ok";
        }
    }
}
