package com.offershield.common.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.Role;
import com.offershield.support.IntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(ProblemDetailExceptionHandlerTest.ThrowingController.class)
class ProblemDetailExceptionHandlerTest extends IntegrationTest {

    private static final String INTERNAL_MARKER = "SECRET-INTERNAL-MARKER";
    private static final String VALUE_MARKER = "ZEBRA-VALUE-MARKER";

    /** On the root logger, so a leak through any logger (not just the handler's) is caught. */
    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    @BeforeEach
    void attachAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(appender);
    }

    @Test
    void notFound_returns404ProblemJson() throws Exception {
        mockMvc.perform(get("/test/not-found").with(signedIn(Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Submission not found"))
                .andExpect(jsonPath("$.instance").value("/test/not-found"))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void apiException_requestIdEqualsResponseHeader() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/test/not-found").with(signedIn(Role.USER)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse();

        assertRequestIdMatchesHeader(response);
    }

    @Test
    void conflict_returns409() throws Exception {
        mockMvc.perform(get("/test/conflict").with(signedIn(Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Stale version"))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void unprocessable_returns422() throws Exception {
        mockMvc.perform(get("/test/unprocessable").with(signedIn(Role.USER)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Image-only PDF"))
                .andExpect(jsonPath("$.errorCode").value("UNPROCESSABLE"));
    }

    @Test
    void optimisticLockingFailure_returns409ConflictWithoutInternals() throws Exception {
        String body = mockMvc.perform(get("/test/stale-write").with(signedIn(Role.USER)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("This item was changed by someone else. Reload it and try again."))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(INTERNAL_MARKER);
    }

    @Test
    void unexpectedError_returns500WithoutInternals() throws Exception {
        String body = mockMvc.perform(get("/test/boom").with(signedIn(Role.USER)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(INTERNAL_MARKER, "IllegalStateException", "at com.");
    }

    @Test
    void unexpectedError_logsClassOnly_noMessage() throws Exception {
        mockMvc.perform(get("/test/boom").with(signedIn(Role.USER))).andExpect(status().isInternalServerError());

        assertThat(handlerEvents()).isNotEmpty()
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull())
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("IllegalStateException"));
        assertMarkerNeverLogged();
    }

    @Test
    void unexpectedErrorWithCause_logsCauseChainClassNames_noMessages() throws Exception {
        mockMvc.perform(get("/test/boom-wrapped").with(signedIn(Role.USER))).andExpect(status().isInternalServerError());

        assertThat(handlerEvents()).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("type=java.lang.IllegalStateException")
                .contains("causes=java.lang.NumberFormatException"));
        assertMarkerNeverLogged();
    }

    @Test
    void unexpectedErrorThrownInsideJdk_logsFirstOfferShieldFrame() throws Exception {
        mockMvc.perform(get("/test/boom-in-jdk").with(signedIn(Role.USER))).andExpect(status().isInternalServerError());

        assertThat(handlerEvents()).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("type=java.lang.NumberFormatException")
                .contains("java.lang.NumberFormatException.forInputString(")
                .contains("app=" + ThrowingController.class.getName() + ".boomInJdk("));
        assertMarkerNeverLogged();
    }

    @Test
    void unknownPath_returns404ProblemWithErrorCodeAndRequestId() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/does-not-exist").with(signedIn(Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/does-not-exist"))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse();

        assertRequestIdMatchesHeader(response);
    }

    @Test
    void wrongMethod_returns405ProblemWithErrorCode() throws Exception {
        mockMvc.perform(post("/api/ping").with(signedIn(Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.instance").value("/api/ping"))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void wrongContentType_returns415ProblemWithErrorCode() throws Exception {
        mockMvc.perform(post("/test/validated-body").with(signedIn(Role.USER)).with(xsrf(mockMvc))
                        .contentType(MediaType.TEXT_PLAIN).content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void malformedJson_returns400ProblemWithErrorCode() throws Exception {
        mockMvc.perform(post("/test/validated-body").with(signedIn(Role.USER)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    /** Spring's default detail would be "Failed to convert 'id' with value: '...'". */
    @Test
    void badPathVariable_returns400WithoutEchoingValueInDetail() throws Exception {
        mockMvc.perform(get("/test/typed/" + VALUE_MARKER).with(signedIn(Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid value for 'id'."))
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void invalidRequestBody_returns400ValidationFailedWithFieldNamesOnly() throws Exception {
        String body = mockMvc.perform(post("/test/validated-body").with(signedIn(Role.USER)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"code\":\"" + VALUE_MARKER + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/test/validated-body"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.violations[*].field").value(containsInAnyOrder("name", "code")))
                .andExpect(jsonPath("$.violations[0].message").isNotEmpty())
                .andExpect(jsonPath("$.violations[1].message").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(VALUE_MARKER);
    }

    @Test
    void invalidRequestParameter_returns400ValidationFailedWithParameterName() throws Exception {
        String body = mockMvc.perform(get("/test/validated-param").param("q", VALUE_MARKER).with(signedIn(Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("q"))
                .andExpect(jsonPath("$.violations[0].message").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(VALUE_MARKER);
    }

    private static void assertRequestIdMatchesHeader(MockHttpServletResponse response) throws Exception {
        String header = response.getHeader("X-Request-Id");
        assertThat(header).isNotBlank();
        String bodyRequestId = JsonPath.read(response.getContentAsString(), "$.requestId");
        assertThat(bodyRequestId).isEqualTo(header);
    }

    private List<ILoggingEvent> handlerEvents() {
        return appender.list.stream()
                .filter(event -> event.getLoggerName().equals(ProblemDetailExceptionHandler.class.getName()))
                .toList();
    }

    private void assertMarkerNeverLogged() {
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(INTERNAL_MARKER);
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertThat(String.valueOf(proxy.getMessage())).doesNotContain(INTERNAL_MARKER);
            }
        });
    }

    record TestBody(@NotBlank String name, @Size(max = 5) String code) {
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("Submission not found");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException("Stale version");
        }

        @GetMapping("/test/unprocessable")
        void unprocessable() {
            throw new UnprocessableException("Image-only PDF");
        }

        @GetMapping("/test/stale-write")
        void staleWrite() {
            throw new OptimisticLockingFailureException("Row was updated by another transaction " + INTERNAL_MARKER);
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException(INTERNAL_MARKER);
        }

        @GetMapping("/test/boom-wrapped")
        void boomWrapped() {
            try {
                Integer.parseInt(INTERNAL_MARKER);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException(INTERNAL_MARKER, ex);
            }
        }

        @GetMapping("/test/boom-in-jdk")
        int boomInJdk() {
            return Integer.parseInt(INTERNAL_MARKER);
        }

        @GetMapping("/test/typed/{id}")
        String typed(@PathVariable UUID id) {
            return id.toString();
        }

        @PostMapping("/test/validated-body")
        String validatedBody(@Valid @RequestBody TestBody body) {
            return body.name();
        }

        @GetMapping("/test/validated-param")
        String validatedParam(@RequestParam @Size(max = 3) String q) {
            return q;
        }
    }
}
