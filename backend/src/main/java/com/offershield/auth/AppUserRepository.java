package com.offershield.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByGoogleSub(String googleSub);

    /** Just the role column, for the per-request session role check. */
    @Query("select u.role from AppUser u where u.id = :id")
    Optional<Role> findRoleById(@Param("id") UUID id);

    /** Not unique: an address can move between Google accounts, and the collation also matches case/accent variants. */
    List<AppUser> findAllByEmail(String email);

    List<AppUser> findByTpoCollegeIdIsNotNull();
}
