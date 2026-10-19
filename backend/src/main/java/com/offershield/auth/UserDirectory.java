package com.offershield.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** The auth feature's read-only view of users for other features; they never use {@link AppUserRepository}. */
@Service
public class UserDirectory {

    private final AppUserRepository repository;

    public UserDirectory(AppUserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<UserContact> findContact(UUID userId) {
        return repository.findById(userId).map(UserDirectory::contact);
    }

    /** Contacts for many users in one query (e.g. a TPO's queue), keyed by user ID. Unknown IDs are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, UserContact> findContacts(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(new LinkedHashSet<>(userIds)).stream()
                .collect(Collectors.toMap(AppUser::getId, UserDirectory::contact));
    }

    /** The college a TPO manages, read from the database (fresh even if the session role is older). */
    @Transactional(readOnly = true)
    public Optional<UUID> findTpoCollegeId(UUID userId) {
        return repository.findById(userId).map(AppUser::getTpoCollegeId);
    }

    private static UserContact contact(AppUser user) {
        return new UserContact(user.getId(), user.getEmail(), user.getName(), user.isEmailVerified());
    }
}
