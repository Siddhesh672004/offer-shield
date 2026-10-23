package com.offershield.common.kafka;

import com.offershield.common.events.Topics;
import com.offershield.common.logging.LogContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumer error handling for every listener (PRD §13.5, D-28): 3 retries with backoff (1, 2, 4 s), then the record
 * goes to {@code <topic>.DLT} on the same partition (the name is set explicitly; Spring Kafka 4's default is
 * {@code -dlt}, V-05). Records that cannot succeed (malformed JSON, wrong type, {@link InvalidEventException}) go to
 * the DLT at once. A record that failed to deserialize is dead-lettered with its original bytes.
 *
 * <p>D-27: the DLT gets the exception class names but not the exception message or stack trace (a parse error can
 * quote the record), Spring's own retry/recovery logging is turned down to DEBUG, and one WARN line per dead-lettered
 * record names topic, partition, offset and the exception class only. Each dead-lettered record is also counted in the
 * Micrometer counter {@value #DEAD_LETTERS_METRIC} (tag {@code topic} only). The listener method calls are logged by
 * the {@code LoggingAspect} (class names only).
 *
 * <p>Deserialization, consumer groups and the DLT topics: see {@code application.yml}, the {@code @KafkaListener}s and
 * {@link KafkaTopicsConfig}.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    static final int RETRIES = 3;
    static final long FIRST_BACKOFF_MS = 1_000;
    static final long MAX_BACKOFF_MS = 4_000;
    /** Micrometer counter of dead-lettered records, one per topic (P13, silent failure 1). */
    public static final String DEAD_LETTERS_METRIC = "offershield.kafka.dead.letters";
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    DeadLetterProducers deadLetterProducers(KafkaTemplate<Object, Object> jsonTemplate,
                                            ProducerFactory<Object, Object> producerFactory) {
        return new DeadLetterProducers(jsonTemplate, producerFactory);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterProducers producers, ObjectProvider<MeterRegistry> meterRegistry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(producers.templates(),
                (record, exception) -> new TopicPartition(Topics.dlt(record.topic()), record.partition()));
        recoverer.excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
        return errorHandler(recoverer, meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** The error handler around a dead-letter recoverer; public for the listener unit tests. */
    public static DefaultErrorHandler errorHandler(ConsumerAwareRecordRecoverer deadLetter,
                                                   MeterRegistry meterRegistry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRIES);
        backOff.setInitialInterval(FIRST_BACKOFF_MS);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(MAX_BACKOFF_MS);
        DefaultErrorHandler handler = new DefaultErrorHandler(new LoggingRecoverer(deadLetter, meterRegistry), backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setLogLevel(KafkaException.Level.DEBUG);
        return handler;
    }

    /**
     * Dead-letters the record, counts it ({@value #DEAD_LETTERS_METRIC}, tagged with the topic only; P13) and logs one
     * line with class names and the running count only.
     */
    private record LoggingRecoverer(ConsumerAwareRecordRecoverer delegate, MeterRegistry meterRegistry)
            implements ConsumerAwareRecordRecoverer {

        @Override
        public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
            delegate.accept(record, consumer, exception);
            Counter deadLetters = Counter.builder(DEAD_LETTERS_METRIC)
                    .description("Records written to a dead-letter topic since startup")
                    .tag("topic", record.topic())
                    .register(meterRegistry);
            deadLetters.increment();
            // The record's own log context is already cleared when the error handler runs: set it again for this line.
            boolean ownContext = MDC.get(LogContext.REQUEST_ID) == null;
            if (ownContext) {
                KafkaLogContextInterceptor.putContext(record);
            }
            try {
                log.warn("event dead-lettered topic={} partition={} offset={} error={} deadLettersOnTopic={}",
                        record.topic(), record.partition(), record.offset(),
                        rootCause(exception).getClass().getSimpleName(), (long) deadLetters.count());
            } finally {
                if (ownContext) {
                    KafkaLogContextInterceptor.clearContext();
                }
            }
        }

        private static Throwable rootCause(Throwable exception) {
            Throwable cause = exception;
            for (int depth = 0; cause.getCause() != null && cause.getCause() != cause && depth < 10; depth++) {
                cause = cause.getCause();
            }
            return cause;
        }
    }

    /**
     * The DLT templates: the application's JSON template for records that were read, and a byte-array template (same
     * producer settings) for records whose original bytes could not be deserialized. Owns the byte-array producer.
     */
    static final class DeadLetterProducers implements DisposableBean {

        private final KafkaTemplate<Object, Object> jsonTemplate;
        private final ProducerFactory<Object, Object> bytesProducerFactory;
        private final KafkaTemplate<Object, Object> bytesTemplate;

        DeadLetterProducers(KafkaTemplate<Object, Object> jsonTemplate, ProducerFactory<Object, Object> producerFactory) {
            this.jsonTemplate = jsonTemplate;
            this.bytesProducerFactory = producerFactory.copyWithConfigurationOverride(
                    Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
            this.bytesTemplate = new KafkaTemplate<>(bytesProducerFactory);
        }

        Map<Class<?>, KafkaOperations<?, ?>> templates() {
            Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
            templates.put(byte[].class, bytesTemplate);
            templates.put(Object.class, jsonTemplate);
            return templates;
        }

        @Override
        public void destroy() {
            if (bytesProducerFactory instanceof DisposableBean disposable) {
                try {
                    disposable.destroy();
                } catch (Exception ex) {
                    log.warn("dead-letter producer close failed: {}", ex.getClass().getSimpleName());
                }
            }
        }
    }
}
