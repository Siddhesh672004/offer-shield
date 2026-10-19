package com.offershield.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByGoogleSub(String googleSub);

    /** Not unique: an address can move between Google accounts, and the collation also matches case/accent variants. */
    List<AppUser> findAllByEmail(String email);

    List<AppUser> findByTpoCollegeIdIsNotNull();
}
