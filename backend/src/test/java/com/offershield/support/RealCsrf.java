package com.offershield.support;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Sends a real XSRF-TOKEN cookie plus the matching X-XSRF-TOKEN header, exactly like Angular does.
 * Don't use spring-security-test's {@code csrf()}: it swaps the token repository inside the shared
 * CsrfFilter, so later tests in the same cached context stop receiving the XSRF-TOKEN cookie.
 */
public final class RealCsrf {

    private RealCsrf() {
    }

    public static RequestPostProcessor xsrf(MockMvc mockMvc) throws Exception {
        Cookie cookie = mockMvc.perform(get("/api/ping")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        if (cookie == null) {
            throw new IllegalStateException("No XSRF-TOKEN cookie issued by GET /api/ping");
        }
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-XSRF-TOKEN", cookie.getValue());
            return request;
        };
    }
}
