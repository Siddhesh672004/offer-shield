package com.offershield.common.concurrency;

import com.google.common.util.concurrent.Striped;
import com.offershield.common.web.ServiceUnavailableException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Per-user locks that make a "count, then insert" limit exact (D-12 upload limit, the scam-report daily limit; P13
 * security review S3). The caller takes the user's lock first thing in its transaction; it is released when that
 * transaction has committed or rolled back, so the next request of the same user counts the committed row. The app
 * runs as a single instance (D-01), so an in-JVM lock is enough. Users are hashed onto a fixed number of stripes
 * (Guava {@link Striped}): two users who share a stripe only wait for each other's short transaction. A request that
 * cannot get the lock within {@link #MAX_WAIT} gets 503 {@code SERVER_BUSY} instead of holding its connection longer.
 * Each owner (a service) keeps its own instance, so different limits never wait for each other.
 */
public final class UserLocks {

    static final int STRIPES = 64;
    static final Duration MAX_WAIT = Duration.ofSeconds(10);

    private final Striped<Lock> locks = Striped.lock(STRIPES);

    /**
     * Locks {@code userId} until the current transaction ends.
     *
     * @throws IllegalStateException       when no transaction is active (a programming error)
     * @throws ServiceUnavailableException when the lock is not free within {@link #MAX_WAIT}
     */
    public void lockUntilTransactionEnds(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A per-user lock needs an active transaction");
        }
        Lock lock = locks.get(userId);
        boolean locked;
        try {
            locked = lock.tryLock(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        if (!locked) {
            throw busy();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                lock.unlock();
            }
        });
    }

    private static ServiceUnavailableException busy() {
        return new ServiceUnavailableException("SERVER_BUSY", "Too many requests at once. Please try again in a minute.");
    }
}
