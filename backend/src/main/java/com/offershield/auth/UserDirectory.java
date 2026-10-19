package com.offershield.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** The auth feature's read-only view of users for other features; they never use {@link AppUserRepository}. */
@Service
public class UserDirectory {

    private final AppUserRepository repository;

    public UserDirectory(AppUserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<UserContact> findContact(UUID userId) {
        return repository.findById(userId)
                .map(user -> new UserContact(user.getId(), user.getEmail(), user.getName(), user.isEmailVerified()));
    }

    /** The college a TPO manages, read from the database (fresh even if the session role is older). */
    @Transactional(readOnly = true)
    public Optional<UUID> findTpoCollegeId(UUID userId) {
        return repository.findById(userId).map(AppUser::getTpoCollegeId);
    }
}
