package com.offershield.common.db;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.offershield.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-27: MySQL error text echoes column values (e.g. "Duplicate entry 'x@gmail.com'"), so no logger may record it. */
class SqlErrorLoggingIT extends IntegrationTest {

    private static final String MARKER = "zebra.marker.sql@example.com";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    @BeforeEach
    void attachRootAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachRootAppender() {
        rootLogger.detachAppender(appender);
    }

    @Test
    void duplicateKeyViaHibernate_valueNeverLogged() {
        insertProcessedEvent(MARKER);

        assertThatThrownBy(() -> insertProcessedEvent(MARKER)).isInstanceOf(PersistenceException.class);

        assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(MARKER));
    }

    private void insertProcessedEvent(String eventId) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("INSERT INTO processed_event (consumer, event_id) VALUES ('sql-logging-test', :eventId)")
                .setParameter("eventId", eventId)
                .executeUpdate());
    }
}
