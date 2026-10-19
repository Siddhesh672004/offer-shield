package com.offershield.membership;

import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A student's request to join a college. State machine (02 §7): request → PENDING → APPROVED / REJECTED by that
 * college's TPO; REJECTED → PENDING when the student re-applies (D-33.3). One row per student.
 */
@Entity
@Table(name = "membership")
public class Membership extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "college_id", nullable = false)
    private UUID collegeId;

    @Column(name = "roll_no", nullable = false, length = 50)
    private String rollNo;

    @Column(name = "department", nullable = false, length = 100)
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MembershipStatus status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Optimistic lock; decisions send it back as {@code If-Match} (a stale version gives 409). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** For JPA only. */
    protected Membership() {
    }

    private Membership(UUID userId, UUID collegeId, String rollNo, String department) {
        super(Ids.newId());
        this.userId = userId;
        this.collegeId = collegeId;
        this.rollNo = rollNo;
        this.department = department;
        this.status = MembershipStatus.PENDING;
    }

    public static Membership request(UUID userId, UUID collegeId, String rollNo, String department) {
        return new Membership(userId, collegeId, rollNo, department);
    }

    public void approve(UUID tpoUserId, Instant at) {
        requirePending();
        this.status = MembershipStatus.APPROVED;
        this.reason = null;
        this.decidedBy = tpoUserId;
        this.decidedAt = at;
    }

    public void reject(UUID tpoUserId, String reason, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to reject");
        }
        requirePending();
        this.status = MembershipStatus.REJECTED;
        this.reason = reason;
        this.decidedBy = tpoUserId;
        this.decidedAt = at;
    }

    /** Same row back to PENDING, possibly for another college; the earlier decision is cleared. */
    public void reapply(UUID collegeId, String rollNo, String department) {
        if (status != MembershipStatus.REJECTED) {
            throw new IllegalStateException("Only a rejected request can be made again");
        }
        this.collegeId = collegeId;
        this.rollNo = rollNo;
        this.department = department;
        this.status = MembershipStatus.PENDING;
        this.reason = null;
        this.decidedBy = null;
        this.decidedAt = null;
    }

    public boolean isPending() {
        return status == MembershipStatus.PENDING;
    }

    private void requirePending() {
        if (status != MembershipStatus.PENDING) {
            throw new IllegalStateException("The request has already been decided");
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCollegeId() {
        return collegeId;
    }

    public String getRollNo() {
        return rollNo;
    }

    public String getDepartment() {
        return department;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Long getVersion() {
        return version;
    }
}
