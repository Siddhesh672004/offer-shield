package com.offershield.common.kafka;

import com.offershield.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;

/**
 * Both topics and their dead-letter topics (D-20, D-28), created at startup by Spring's {@code KafkaAdmin} because the
 * broker never auto-creates topics. One broker, so one replica. A DLT has as many partitions as its topic, since a
 * failed record is dead-lettered to the same partition number. Retention: 3 days for topics, 7 for DLTs (D-06).
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicsConfig {

    static final int PARTITIONS = 3;
    static final int REPLICAS = 1;
    static final Duration TOPIC_RETENTION = Duration.ofDays(3);
    static final Duration DLT_RETENTION = Duration.ofDays(7);

    @Bean
    KafkaAdmin.NewTopics offerShieldTopics() {
        return new KafkaAdmin.NewTopics(
                topic(Topics.SUBMISSION_CREATED, TOPIC_RETENTION),
                topic(Topics.dlt(Topics.SUBMISSION_CREATED), DLT_RETENTION),
                topic(Topics.ANALYSIS_COMPLETED, TOPIC_RETENTION),
                topic(Topics.dlt(Topics.ANALYSIS_COMPLETED), DLT_RETENTION));
    }

    private static NewTopic topic(String name, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
                .build();
    }
}
