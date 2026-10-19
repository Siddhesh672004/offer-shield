package com.offershield.auth;

import com.offershield.common.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemResponseWriter;

    public ProblemDetailAccessDeniedHandler(ProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        if (accessDeniedException instanceof CsrfException) {
            problemResponseWriter.write(request, response, HttpStatus.FORBIDDEN,
                    "Missing or invalid CSRF token. Reload the page and try again.", "CSRF_INVALID");
            return;
        }
        problemResponseWriter.write(request, response, HttpStatus.FORBIDDEN, "You do not have permission to do this.", "FORBIDDEN");
    }
}
