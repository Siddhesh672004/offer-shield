package com.offershield.common.concurrency;

import com.offershield.support.NoOpTransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** P13 (S3): a user's lock is held from the call until the caller's transaction ends (commit or rollback). */
class UserLocksTest {

    private final UserLocks locks = new UserLocks();
    private final TransactionTemplate transaction = new TransactionTemplate(new NoOpTransactionManager());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void heldUntilTheTransactionCommits_thenTheNextRequestGetsIt() throws Exception {
        UUID user = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            locks.lockUntilTransactionEnds(user);
            locked.countDown();
            awaitQuietly(release);
        }), pool);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Thread> secondThread = new AtomicReference<>();
        AtomicBoolean secondLocked = new AtomicBoolean();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            secondThread.set(Thread.currentThread());
            transaction.executeWithoutResult(status -> {
                locks.lockUntilTransactionEnds(user);
                secondLocked.set(true);
            });
        }, pool);
        await().atMost(Duration.ofSeconds(5)).until(() -> secondThread.get() != null
                && secondThread.get().getState() == Thread.State.TIMED_WAITING);
        assertThat(secondLocked).as("waits while the first transaction is open").isFalse();

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertThat(secondLocked).isTrue();
    }

    @Test
    void releasedAfterARollbackToo() throws Exception {
        UUID user = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            locks.lockUntilTransactionEnds(user);
            status.setRollbackOnly();
        });

        Boolean otherThreadLocked = CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
            locks.lockUntilTransactionEnds(user);
            return true;
        }), pool).get(2, TimeUnit.SECONDS);

        assertThat(otherThreadLocked).isTrue();
    }

    @Test
    void withoutATransaction_refused() {
        assertThatThrownBy(() -> locks.lockUntilTransactionEnds(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
