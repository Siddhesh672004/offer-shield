package com.offershield.auth;

import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.util.UUID;

/**
 * A Google account that has signed in. The row is keyed on the OIDC {@code sub}; the email is display/matching data
 * only. {@code @DynamicUpdate}: the table has no version column, so an UPDATE writes only the columns that changed; a
 * sign-in that read the row before an admin's TPO promotion committed then can't write the old role/college back.
 */
@Entity
@Table(name = "app_user")
@DynamicUpdate
public class AppUser extends BaseEntity {

    @Column(name = "google_sub", nullable = false, updatable = false, length = 255)
    private String googleSub;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "name", length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    /** Set once a TPO assignment is consumed (R-C); points at the college this TPO reviews for. */
    @Column(name = "tpo_college_id")
    private UUID tpoCollegeId;

    /** For JPA only. */
    protected AppUser() {
    }

    private AppUser(String googleSub, String email, boolean emailVerified, String name) {
        super(Ids.newId());
        this.googleSub = googleSub;
        this.email = email;
        this.emailVerified = emailVerified;
        this.name = name;
        this.role = Role.USER;
    }

    public static AppUser create(String googleSub, String email, boolean emailVerified, String name) {
        return new AppUser(googleSub, email, emailVerified, name);
    }

    public void updateIdentity(String email, boolean emailVerified, String name) {
        this.email = email;
        this.emailVerified = emailVerified;
        this.name = name;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public void assignTpoCollege(UUID collegeId) {
        this.tpoCollegeId = collegeId;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public UUID getTpoCollegeId() {
        return tpoCollegeId;
    }
}
