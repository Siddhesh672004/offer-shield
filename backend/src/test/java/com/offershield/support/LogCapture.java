package com.offershield.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures log events from every thread (e.g. the async email pool). Each event's thread name, MDC and message are
 * fixed when it is logged, and {@link #events()} is safe to read while other threads are still logging.
 */
public final class LogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger;

    private LogCapture(Logger logger) {
        this.logger = logger;
    }

    public static LogCapture root() {
        return attach(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    public static LogCapture of(Class<?> type) {
        return attach(type.getName());
    }

    private static LogCapture attach(String loggerName) {
        LogCapture capture = new LogCapture((Logger) LoggerFactory.getLogger(loggerName));
        capture.setContext(capture.logger.getLoggerContext());
        capture.start();
        capture.logger.addAppender(capture);
        return capture;
    }

    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(event);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        logger.detachAppender(this);
        stop();
    }
}
