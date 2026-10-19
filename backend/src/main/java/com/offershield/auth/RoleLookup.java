package com.offershield.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * A user's current role as stored in the database, behind a short in-memory cache so checking it on every request
 * costs at most one small query per user every 30 seconds. Whoever changes a role calls {@link #evict} so the change
 * shows on that user's very next request.
 */
@Service
public class RoleLookup {

    static final Duration TTL = Duration.ofSeconds(30);
    private static final long MAX_CACHED_USERS = 10_000;

    private final AppUserRepository users;
    private final Cache<UUID, Role> roles;

    @Autowired
    public RoleLookup(AppUserRepository users) {
        this(users, TTL, Ticker.systemTicker());
    }

    RoleLookup(AppUserRepository users, Duration ttl, Ticker ticker) {
        this.users = users;
        this.roles = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(MAX_CACHED_USERS)
                .ticker(ticker)
                .build();
    }

    /** Empty when the user row no longer exists; nothing is cached in that case. */
    public Optional<Role> findRole(UUID userId) {
        return Optional.ofNullable(roles.get(userId, id -> users.findRoleById(id).orElse(null)));
    }

    /**
     * Drops the cached role now and, when called inside a transaction, again once it completes: until the commit a
     * concurrent request can still read the old role and would otherwise cache it for the whole TTL.
     */
    public void evict(UUID userId) {
        roles.invalidate(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    roles.invalidate(userId);
                }
            });
        }
    }
}
