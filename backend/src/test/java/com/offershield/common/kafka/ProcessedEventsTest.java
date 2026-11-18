package com.offershield.common.kafka;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer dedupe on MySQL 8.4 (D-28) and the Kafka admin settings. P13 (silent failures 9 and 13): only a duplicate
 * key means "already processed"; any other insert problem (a value too long, a constraint) fails the consumer's
 * transaction instead of being cut short or ignored, and the application refuses to start when its topics cannot be
 * created.
 */
class ProcessedEventsTest extends IntegrationTest {

    @Autowired
    private ProcessedEvents processedEvents;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void markProcessed_firstTrue_duplicateFalse_workAfterTheDuplicateStillCommits() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String consumer = "p13-dedupe-" + UUID.randomUUID().toString().substring(0, 8);
        UUID eventId = UUID.randomUUID();

        Boolean first = transaction.execute(status -> processedEvents.markProcessed(consumer, eventId));
        Boolean duplicate = transaction.execute(status -> {
            boolean marked = processedEvents.markProcessed(consumer, eventId);
            jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?, ?)", consumer + "-after",
                    eventId.toString());
            return marked;
        });

        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(rows(consumer)).isEqualTo(1);
        assertThat(rows(consumer + "-after")).as("the duplicate did not roll the transaction back").isEqualTo(1);
    }

    @Test
    void markProcessed_valueTooLong_failsInsteadOfBeingCutShort() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String consumer = "p13-too-long-" + "x".repeat(60);

        assertThatThrownBy(() -> transaction.execute(status -> processedEvents.markProcessed(consumer,
                UUID.randomUUID()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rows(consumer.substring(0, 64))).isZero();
    }

    @Test
    void kafkaAdmin_failsStartupWhenTopicsCannotBeCreated() {
        assertThat(ReflectionTestUtils.getField(kafkaAdmin, "fatalIfBrokerNotAvailable")).isEqualTo(true);
    }

    private int rows(String consumer) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ?", Integer.class,
                consumer);
        return count == null ? 0 : count;
    }
}
