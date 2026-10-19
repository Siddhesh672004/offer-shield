package com.offershield.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();
    private final AtomicReference<String> seenRequestId = new AtomicReference<>();
    private final FilterChain capturingChain = (request, response) -> seenRequestId.set(MDC.get(LogContext.REQUEST_ID));

    @Test
    void request_setsMdcAndResponseHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/ping"), response, capturingChain);

        assertThat(seenRequestId.get()).matches(UUID_PATTERN);
        assertThat(response.getHeader(LogContext.REQUEST_ID_HEADER)).isEqualTo(seenRequestId.get());
    }

    @Test
    void incomingHeader_ignored_newIdGenerated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader(LogContext.REQUEST_ID_HEADER, "evil\nINJECT");

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain);

        assertThat(seenRequestId.get()).isNotEqualTo("evil\nINJECT").matches(UUID_PATTERN);
    }

    @Test
    void chainThrows_mdcClearedAndExceptionPropagates() {
        FilterChain throwingChain = (request, response) -> {
            seenRequestId.set(MDC.get(LogContext.REQUEST_ID));
            throw new ServletException("chain failed");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/ping"),
                new MockHttpServletResponse(), throwingChain))
                .isInstanceOf(ServletException.class);

        assertThat(seenRequestId.get()).matches(UUID_PATTERN);
        assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
    }

    @Test
    void afterRequest_mdcCleared() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/ping"), new MockHttpServletResponse(), capturingChain);

        assertThat(seenRequestId.get()).isNotNull();
        assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
    }
}
