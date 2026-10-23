package com.offershield.common.kafka;

import com.offershield.common.events.EventHeaders;
import com.offershield.common.logging.LogContext;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts the request ID (from the {@code requestId} header, D-27) and the submission ID (the record key) into the MDC
 * for every consumed record, so a listener's log lines join the upload's. Values that are not UUIDs are ignored (a
 * fresh request ID is used), so a record cannot inject text into the logs. Cleared after each record.
 */
@Component
public class KafkaLogContextInterceptor implements RecordInterceptor<Object, Object> {

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        putContext(record);
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        clearContext();
    }

    static void putContext(ConsumerRecord<?, ?> record) {
        Header requestId = record.headers().lastHeader(EventHeaders.REQUEST_ID);
        String requestIdText = requestId == null ? null : new String(requestId.value(), StandardCharsets.UTF_8);
        MDC.put(LogContext.REQUEST_ID, isUuid(requestIdText) ? requestIdText : UUID.randomUUID().toString());
        if (record.key() instanceof String key && isUuid(key)) {
            MDC.put(LogContext.SUBMISSION_ID, key);
        }
    }

    static void clearContext() {
        MDC.remove(LogContext.REQUEST_ID);
        MDC.remove(LogContext.SUBMISSION_ID);
        MDC.remove(LogContext.USER_ID);
    }

    private static boolean isUuid(String value) {
        return value != null && UUID_TEXT.matcher(value).matches();
    }
}
