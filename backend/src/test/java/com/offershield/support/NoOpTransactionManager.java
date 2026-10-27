package com.offershield.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager without any resource: begin/commit/rollback do nothing, but transaction synchronization (and
 * so {@code @TransactionalEventListener}) runs exactly as with a real one. For unit tests only.
 */
public final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    private int commits;
    private int rollbacks;

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks++;
    }

    public int commits() {
        return commits;
    }

    public int rollbacks() {
        return rollbacks;
    }
}
