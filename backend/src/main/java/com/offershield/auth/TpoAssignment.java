package com.offershield.auth;

import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** "This email becomes TPO of this college at its first verified sign-in." The email is stored trimmed and lower-case. */
@Entity
@Table(name = "tpo_assignment")
public class TpoAssignment extends BaseEntity {

    @Column(name = "email", nullable = false, updatable = false, length = 320)
    private String email;

    @Column(name = "college_id", nullable = false, updatable = false)
    private UUID collegeId;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    /** For JPA only. */
    protected TpoAssignment() {
    }

    private TpoAssignment(String email, UUID collegeId, UUID assignedBy) {
        super(Ids.newId());
        this.email = email;
        this.collegeId = collegeId;
        this.assignedBy = assignedBy;
    }

    /** {@code email} must already be trimmed and lower-case. */
    public static TpoAssignment create(String email, UUID collegeId, UUID assignedBy) {
        return new TpoAssignment(email, collegeId, assignedBy);
    }

    public String getEmail() {
        return email;
    }

    public UUID getCollegeId() {
        return collegeId;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }
}
