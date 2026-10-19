package com.offershield.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

@RestControllerAdvice
public class ProblemDetailExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.getMessage(), ex.errorCode(), request);
    }

    /**
     * Exception messages can carry personal data (e.g. MySQL "Duplicate entry 'x@gmail.com'"),
     * so only the exception class and the top stack frame are logged (D-27).
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception type={} at={}", ex.getClass().getName(), topFrame(ex));
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", "INTERNAL", request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", errorCode);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }

    private static String topFrame(Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        return trace.length == 0 ? "unknown" : trace[0].toString();
    }
}
