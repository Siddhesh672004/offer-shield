package com.offershield.submission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.offershield.common.web.ApiException;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P13 (silent failures 4 and 11): what a failure while reading a PDF becomes. A broken file is the sender's problem
 * (422 {@code PDF_UNREADABLE}, INFO); an unexpected exception inside the reader is still a 422 but is warned with its
 * class, so a bug is visible; an {@link Error} (out of memory, a missing class) is our problem: 503 and an ERROR line.
 * The timeout and refused paths get their own log lines. Only class names and codes are logged, never a message.
 */
class TextExtractorTest {

    @Test
    void errorWhileReading_503AndLoggedAsError_notCalledUnreadable() {
        List<ILoggingEvent> events;
        RuntimeException translated;
        try (LogCapture logs = LogCapture.of(TextExtractor.class)) {
            translated = TextExtractor.translate(new OutOfMemoryError("SECRET-DETAIL Java heap space"));
            events = logs.events();
        }

        assertThat(translated).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.errorCode()).isEqualTo("PDF_READ_FAILED");
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("OutOfMemoryError").doesNotContain("SECRET-DETAIL");
        });
    }

    @Test
    void unexpectedExceptionInsideTheReader_422ButWarnedWithItsClass() {
        List<ILoggingEvent> events;
        RuntimeException translated;
        try (LogCapture logs = LogCapture.of(TextExtractor.class)) {
            translated = TextExtractor.translate(new IllegalStateException("SECRET-DETAIL"));
            events = logs.events();
        }

        assertThat(translated).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(ex.errorCode()).isEqualTo("PDF_UNREADABLE");
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("IllegalStateException").doesNotContain("SECRET-DETAIL");
        });
    }

    @Test
    void brokenFile_422UnreadableAtInfo() {
        List<ILoggingEvent> events;
        RuntimeException translated;
        try (LogCapture logs = LogCapture.of(TextExtractor.class)) {
            translated = TextExtractor.translate(new IOException("SECRET-DETAIL missing root object"));
            events = logs.events();
        }

        assertThat(translated).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo("PDF_UNREADABLE"));
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("IOException").doesNotContain("SECRET-DETAIL");
        });
    }

    @Test
    void tooSlow_422Timeout_logged() {
        TextExtractor slow = new TextExtractor(Duration.ofNanos(1));
        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(TextExtractor.class)) {
            assertThatThrownBy(() -> slow.extract(TestPdfs.withPages(10, 80)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo("PDF_TIMEOUT"));
            events = logs.events();
        } finally {
            slow.destroy();
        }

        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("errorCode=PDF_TIMEOUT"));
    }

    @Test
    void noWorkerTakesTheFile_503ServerBusy_logged() {
        TextExtractor stopped = new TextExtractor(Duration.ofSeconds(10));
        stopped.destroy();
        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(TextExtractor.class)) {
            assertThatThrownBy(() -> stopped.extract(TestPdfs.onePage()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(ex.errorCode()).isEqualTo("SERVER_BUSY");
                    });
            events = logs.events();
        }

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("errorCode=SERVER_BUSY");
        });
    }
}
