package com.offershield.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingAspectTest {

    private static final String ARG_MARKER = "ZEBRA-MARKER-ARG";
    private static final String ERR_MARKER = "ZEBRA-MARKER-ERR";

    private ListAppender<ILoggingEvent> appender;
    private Logger aspectLogger;
    private SampleService service;

    @BeforeEach
    void setUp() {
        aspectLogger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.setProxyTargetClass(true);
        factory.addAspect(new LoggingAspect());
        service = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(appender);
    }

    @Test
    void logsMethodAndDuration() {
        service.echo("hello");

        assertThat(lines()).singleElement().satisfies(line -> assertThat(line)
                .contains("call=SampleService.echo")
                .contains("durationMs=")
                .contains("outcome=ok"));
    }

    @Test
    void neverLogsArguments() {
        service.echo(ARG_MARKER);

        assertThat(lines()).isNotEmpty().noneMatch(line -> line.contains(ARG_MARKER));
    }

    @Test
    void neverLogsReturnValue() {
        String result = service.echo("value");

        assertThat(result).isEqualTo("RET-value");
        assertThat(lines()).isNotEmpty().noneMatch(line -> line.contains("RET-"));
    }

    @Test
    void exception_loggedAsClassOnly_rethrown() {
        assertThatThrownBy(() -> service.fail(ERR_MARKER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ERR_MARKER);

        assertThat(lines()).singleElement().satisfies(line -> assertThat(line)
                .contains("call=SampleService.fail")
                .contains("outcome=error error=IllegalArgumentException")
                .doesNotContain(ERR_MARKER));
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void restController_loggedWithClassAndMethod() {
        proxy(new SampleController()).handle(ARG_MARKER);

        assertThat(lines()).singleElement().satisfies(line -> assertThat(line)
                .contains("call=SampleController.handle")
                .contains("outcome=ok")
                .doesNotContain(ARG_MARKER));
    }

    @Test
    void kafkaListenerMethod_loggedWithClassAndMethod() {
        proxy(new SampleListener()).onMessage(ARG_MARKER);

        assertThat(lines()).singleElement().satisfies(line -> assertThat(line)
                .contains("call=SampleListener.onMessage")
                .contains("outcome=ok")
                .doesNotContain(ARG_MARKER));
    }

    @Test
    void kafkaListenerFailure_loggedAsClassOnly_rethrown() {
        SampleListener listener = proxy(new SampleListener());

        assertThatThrownBy(() -> listener.onPoison(ERR_MARKER)).isInstanceOf(IllegalStateException.class);

        assertThat(lines()).singleElement().satisfies(line -> assertThat(line)
                .contains("call=SampleListener.onPoison")
                .contains("outcome=error error=IllegalStateException")
                .doesNotContain(ERR_MARKER));
    }

    @Test
    void nonListenerMethodOfListenerBean_notLogged() {
        proxy(new SampleListener()).helper();

        assertThat(lines()).isEmpty();
    }

    @Test
    void plainComponent_notLogged() {
        proxy(new SampleComponent()).work();

        assertThat(lines()).isEmpty();
    }

    private static <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new LoggingAspect());
        return factory.getProxy();
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @RestController
    static class SampleController {

        String handle(String value) {
            return "RET-" + value;
        }
    }

    static class SampleListener {

        @KafkaListener(topics = "sample.topic")
        void onMessage(String payload) {
        }

        @KafkaListener(topics = "sample.poison")
        void onPoison(String payload) {
            throw new IllegalStateException(payload);
        }

        void helper() {
        }
    }

    @Component
    static class SampleComponent {

        void work() {
        }
    }

    @Service
    static class SampleService {

        String echo(String value) {
            return "RET-" + value;
        }

        void fail(String value) {
            throw new IllegalArgumentException(value);
        }
    }
}
