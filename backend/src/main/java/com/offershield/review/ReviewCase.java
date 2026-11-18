package com.offershield.review;

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
 * "Ask my TPO" (PRD §4 F5, §6.5): one case per submission, for the college the student is an approved member of.
 * State machine (02 §7): OPEN → CONFIRMED_SCAM / CONFIRMED_GENUINE / INCONCLUSIVE by a TPO of that college, once, at
 * the version the TPO looked at.
 */
@Entity
@Table(name = "review_case")
public class ReviewCase extends BaseEntity {

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Column(name = "college_id", nullable = false, updatable = false)
    private UUID collegeId;

    @Column(name = "student_user_id", nullable = false, updatable = false)
    private UUID studentUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReviewStatus status;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Optimistic lock; the TPO's decision sends it back as {@code If-Match} (a stale version gives 409). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** For JPA only. */
    protected ReviewCase() {
    }

    private ReviewCase(UUID submissionId, UUID collegeId, UUID studentUserId) {
        super(Ids.newId());
        this.submissionId = submissionId;
        this.collegeId = collegeId;
        this.studentUserId = studentUserId;
        this.status = ReviewStatus.OPEN;
    }

    static ReviewCase open(UUID submissionId, UUID collegeId, UUID studentUserId) {
        return new ReviewCase(submissionId, collegeId, studentUserId);
    }

    void decide(ReviewDecision decision, String note, UUID tpoUserId, Instant at) {
        if (!isOpen()) {
            throw new IllegalStateException("The review case has already been decided");
        }
        this.status = decision.status();
        this.note = note;
        this.reviewerId = tpoUserId;
        this.decidedAt = at;
    }

    boolean isOpen() {
        return status == ReviewStatus.OPEN;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public UUID getCollegeId() {
        return collegeId;
    }

    public UUID getStudentUserId() {
        return studentUserId;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Long getVersion() {
        return version;
    }
}
