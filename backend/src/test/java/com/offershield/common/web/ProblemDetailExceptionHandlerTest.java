package com.offershield.common.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import(ProblemDetailExceptionHandlerTest.ThrowingController.class)
class ProblemDetailExceptionHandlerTest {

    private static final String INTERNAL_MARKER = "SECRET-INTERNAL-MARKER";

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> appender;
    private Logger handlerLogger;

    @BeforeEach
    void attachAppender() {
        handlerLogger = (Logger) LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        handlerLogger.detachAppender(appender);
    }

    @Test
    void notFound_returns404ProblemJson() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Submission not found"))
                .andExpect(jsonPath("$.instance").value("/test/not-found"))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void conflict_returns409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Stale version"))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void unprocessable_returns422() throws Exception {
        mockMvc.perform(get("/test/unprocessable"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Image-only PDF"))
                .andExpect(jsonPath("$.errorCode").value("UNPROCESSABLE"));
    }

    @Test
    void unexpectedError_returns500WithoutInternals() throws Exception {
        String body = mockMvc.perform(get("/test/boom"))
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
        mockMvc.perform(get("/test/boom")).andExpect(status().isInternalServerError());

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(INTERNAL_MARKER);
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("IllegalStateException"));
    }

    @Test
    void unknownPath_returns404Problem() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
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

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException(INTERNAL_MARKER);
        }
    }
}
