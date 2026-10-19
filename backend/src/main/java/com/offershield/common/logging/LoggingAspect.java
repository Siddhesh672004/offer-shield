package com.offershield.common.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs class.method, duration and outcome of controllers, services and Kafka listeners (D-27).
 * Arguments, return values and exception messages are never logged; request, user and
 * submission IDs come from the MDC via the log pattern.
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    @Around("within(com.offershield..*) && ("
            + "within(@org.springframework.web.bind.annotation.RestController *)"
            + " || within(@org.springframework.stereotype.Service *)"
            + " || @annotation(org.springframework.kafka.annotation.KafkaListener))")
    public Object logCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String call = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            log.info("call={} durationMs={} outcome=ok", call, elapsedMs(start));
            return result;
        } catch (Throwable ex) {
            log.warn("call={} durationMs={} outcome=error error={}", call, elapsedMs(start), ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
