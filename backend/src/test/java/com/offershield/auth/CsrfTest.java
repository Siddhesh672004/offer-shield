package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CsrfTest extends IntegrationTest {

    @Test
    void getIssuesXsrfCookie() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void postWithoutToken_403() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(signedIn(Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
    }

    @Test
    void postWithToken_ok() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(signedIn(Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isNoContent());
    }

    @Test
    void postWithMismatchedHeader_403() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(signedIn(Role.USER))
                        .cookie(new Cookie("XSRF-TOKEN", "cookie-value"))
                        .header("X-XSRF-TOKEN", "different-value"))
                .andExpect(status().isForbidden());
    }
}
