package com.offershield.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleLookupTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RoleLookup roleLookup = new RoleLookup(users, Duration.ofSeconds(30), () -> 0L);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void findRole_cached_secondCallSkipsDatabase() {
        when(users.findRoleById(userId)).thenReturn(Optional.of(Role.USER));

        assertThat(roleLookup.findRole(userId)).contains(Role.USER);
        assertThat(roleLookup.findRole(userId)).contains(Role.USER);

        verify(users, times(1)).findRoleById(userId);
    }

    @Test
    void evict_nextLookupReadsDatabase() {
        when(users.findRoleById(userId)).thenReturn(Optional.of(Role.USER), Optional.of(Role.TPO));
        roleLookup.findRole(userId);

        roleLookup.evict(userId);

        assertThat(roleLookup.findRole(userId)).contains(Role.TPO);
    }

    @Test
    void findRole_unknownUser_emptyAndNotCached() {
        when(users.findRoleById(userId)).thenReturn(Optional.empty());

        assertThat(roleLookup.findRole(userId)).isEmpty();
        assertThat(roleLookup.findRole(userId)).isEmpty();

        verify(users, times(2)).findRoleById(userId);
    }

    /**
     * Inside the promoting transaction, a concurrent request can still read (and cache) the old role before the commit;
     * the entry is dropped again once the transaction completes, so the new role shows on the next request.
     */
    @Test
    void evict_insideTransaction_evictedAgainAfterCompletion() {
        when(users.findRoleById(userId)).thenReturn(Optional.of(Role.USER), Optional.of(Role.USER), Optional.of(Role.TPO));
        roleLookup.findRole(userId);
        TransactionSynchronizationManager.initSynchronization();

        roleLookup.evict(userId);
        assertThat(roleLookup.findRole(userId)).as("read before commit").contains(Role.USER);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        assertThat(roleLookup.findRole(userId)).contains(Role.TPO);
    }
}
