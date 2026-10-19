package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityHeadersTest extends IntegrationTest {

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Test
    void apiResponse_hasSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void secureRequest_hasHsts() throws Exception {
        mockMvc.perform(get("/api/ping").secure(true))
                .andExpect(header().exists("Strict-Transport-Security"));
    }

    @Test
    void plainHttpRequest_noHsts() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
