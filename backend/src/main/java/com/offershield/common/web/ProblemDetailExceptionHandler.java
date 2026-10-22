package com.offershield.common.web;

import com.offershield.common.logging.LogContext;
import com.offershield.common.logging.LoggingAspect;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Every error leaves as an RFC 9457 problem with {@code type, title, status, detail, instance} plus our
 * {@code errorCode} and {@code requestId}: our {@link ApiException}s, Spring MVC's own errors (404, 405, 400, 415, ...)
 * and anything unexpected (500). Nothing here echoes a submitted value or an exception message (D-27).
 */
@RestControllerAdvice
public class ProblemDetailExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);
    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final String APP_PACKAGE = "com.offershield.";
    private static final int MAX_CAUSES = 10;

    /** One failed constraint: the field or parameter name and the constraint's message, never the rejected value. */
    public record Violation(String field, String message) {
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex, HttpServletRequest request, HttpServletResponse response) {
        if (ex instanceof TooManyRequestsException tooMany) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(tooMany.retryAfterSeconds()));
        }
        if (ex.status().is5xxServerError()) {
            // P13: a 5xx we chose (SERVER_BUSY, STORAGE_UNAVAILABLE, ...) still means something on our side is short.
            log.warn("problem status={} errorCode={}", ex.status().value(), ex.errorCode());
        }
        return problem(ex.status(), ex.getMessage(), ex.errorCode(), request);
    }

    /** A malformed multipart body (bad boundary, truncated upload): the client's fault, not a 500. */
    @ExceptionHandler(MultipartException.class)
    ProblemDetail handleMultipart(MultipartException ex, HttpServletRequest request) {
        // P13: class names only; the message can quote the request.
        log.info("problem status=400 errorCode=MULTIPART_INVALID type={} causes={}", ex.getClass().getSimpleName(),
                causeChain(ex));
        return problem(HttpStatus.BAD_REQUEST, "The upload could not be read. Please try again.", "MULTIPART_INVALID",
                request);
    }

    /** The servlet container refused a part over {@code spring.servlet.multipart.max-file-size} (5 MB). */
    @Override
    protected @Nullable ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "The file is larger than 5 MB.");
        problem.setProperty("errorCode", "FILE_TOO_LARGE");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
    }

    /** {@code @PreAuthorize} denials surface inside MVC; without this they would hit the 500 catch-all. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to do this.", "FORBIDDEN", request);
    }

    /** A JPA {@code @Version} clash: someone else saved first. The message names the entity and id, so it's dropped. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "This item was changed by someone else. Reload it and try again.",
                "CONFLICT", request);
    }

    /**
     * Exception messages can carry personal data (e.g. MySQL "Duplicate entry 'x@gmail.com'"), so only class names,
     * the top stack frame and the first frame in our own code are logged (D-27).
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        logUnexpected(ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", "INTERNAL", request);
    }

    /** {@code @Valid @RequestBody} failures. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> violations.add(violation(error)));
        ex.getBindingResult().getGlobalErrors().forEach(error -> violations.add(violation(error)));
        return handleExceptionInternal(ex, validationProblem(violations), headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Constraint annotations on controller parameters ({@code @RequestParam @Size ...}), checked by Spring MVC. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.isForReturnValue()) {
            return super.handleHandlerMethodValidationException(ex, headers, status, request);
        }
        List<Violation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                errors.getFieldErrors().forEach(error -> violations.add(violation(error)));
                errors.getGlobalErrors().forEach(error -> violations.add(violation(error)));
            } else {
                String name = String.valueOf(result.getMethodParameter().getParameterName());
                result.getResolvableErrors().forEach(error -> violations.add(new Violation(name, message(error))));
            }
        }
        return handleExceptionInternal(ex, validationProblem(violations), headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Spring's default detail repeats the submitted value ("Failed to convert 'id' with value: '...'"). */
    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = ex.getPropertyName() == null ? "Invalid value." : "Invalid value for '" + ex.getPropertyName() + "'.";
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail), headers, status, request);
    }

    /** Every Spring MVC error ends here: add the fields our own problems carry. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            logUnexpected(ex);
        }
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            complete(problem, errorCode(statusCode), requestUri(request));
        }
        return response;
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        complete(problem, errorCode, request.getRequestURI());
        return problem;
    }

    private static ProblemDetail validationProblem(List<Violation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Some fields are missing or invalid.");
        problem.setProperty("errorCode", VALIDATION_FAILED);
        violations.sort(Comparator.comparing(Violation::field).thenComparing(Violation::message));
        problem.setProperty("violations", violations);
        return problem;
    }

    /** Fills whatever is missing; an {@code errorCode} set by a more specific handler is kept. */
    private static void complete(ProblemDetail problem, String errorCode, @Nullable String requestUri) {
        if (problem.getType() == null) {
            problem.setType(ABOUT_BLANK);
        }
        if (problem.getTitle() == null) {
            HttpStatus status = HttpStatus.resolve(problem.getStatus());
            problem.setTitle(status != null ? status.getReasonPhrase() : "Error");
        }
        if (problem.getInstance() == null && requestUri != null) {
            problem.setInstance(URI.create(requestUri));
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("errorCode")) {
            problem.setProperty("errorCode", errorCode);
        }
        String requestId = MDC.get(LogContext.REQUEST_ID);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }

    private static String errorCode(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return "INTERNAL";
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }

    private static @Nullable String requestUri(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI() : null;
    }

    private static Violation violation(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        return new Violation(field, message(error));
    }

    private static String message(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid";
    }

    private static void logUnexpected(Throwable ex) {
        log.error("Unhandled exception type={} causes={} at={} app={}",
                ex.getClass().getName(), causeChain(ex), topFrame(ex), firstAppFrame(ex));
    }

    /** Class names of the causes, outermost first; guarded against cycles. */
    private static String causeChain(Throwable ex) {
        StringJoiner chain = new StringJoiner(" > ");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(ex);
        for (Throwable cause = ex.getCause(); cause != null && seen.add(cause) && seen.size() <= MAX_CAUSES;
             cause = cause.getCause()) {
            chain.add(cause.getClass().getName());
        }
        return chain.length() == 0 ? "none" : chain.toString();
    }

    private static String topFrame(Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        return trace.length == 0 ? "unknown" : trace[0].toString();
    }

    /** The first frame in our code (skipping proxies and the logging aspect), searching the exception then its causes. */
    private static String firstAppFrame(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = ex; current != null && seen.add(current) && seen.size() <= MAX_CAUSES;
             current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String className = frame.getClassName();
                if (className.startsWith(APP_PACKAGE) && !className.contains("$$")
                        && !className.equals(LoggingAspect.class.getName())) {
                    return frame.toString();
                }
            }
        }
        return "unknown";
    }
}
