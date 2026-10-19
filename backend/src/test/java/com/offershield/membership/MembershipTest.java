package com.offershield.membership;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipTest {

    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID COLLEGE = UUID.randomUUID();
    private static final UUID TPO = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-20T10:15:30Z");

    @Test
    void request_new_pendingWithoutDecision() {
        Membership membership = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");

        assertThat(membership.getId()).isNotNull();
        assertThat(membership.getUserId()).isEqualTo(STUDENT);
        assertThat(membership.getCollegeId()).isEqualTo(COLLEGE);
        assertThat(membership.getRollNo()).isEqualTo("CS-042");
        assertThat(membership.getDepartment()).isEqualTo("Computer Engineering");
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(membership.getReason()).isNull();
        assertThat(membership.getDecidedBy()).isNull();
        assertThat(membership.getDecidedAt()).isNull();
    }

    @Test
    void approve_pending_approvedByTpo() {
        Membership membership = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");

        membership.approve(TPO, NOW);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.APPROVED);
        assertThat(membership.getDecidedBy()).isEqualTo(TPO);
        assertThat(membership.getDecidedAt()).isEqualTo(NOW);
        assertThat(membership.getReason()).isNull();
    }

    @Test
    void reject_pending_rejectedWithReason() {
        Membership membership = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");

        membership.reject(TPO, "Roll number not on our list", NOW);

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.REJECTED);
        assertThat(membership.getReason()).isEqualTo("Roll number not on our list");
        assertThat(membership.getDecidedBy()).isEqualTo(TPO);
        assertThat(membership.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void approve_alreadyDecided_throws() {
        Membership membership = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");
        membership.approve(TPO, NOW);

        assertThatThrownBy(() -> membership.approve(TPO, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> membership.reject(TPO, "late", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reject_blankReason_throws() {
        Membership membership = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");

        assertThatThrownBy(() -> membership.reject(TPO, " ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.PENDING);
    }

    @Test
    void reapply_rejected_pendingAgainWithDecisionCleared() {
        Membership membership = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");
        membership.reject(TPO, "Wrong department", NOW);
        UUID otherCollege = UUID.randomUUID();

        membership.reapply(otherCollege, "IT-007", "Information Technology");

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(membership.getCollegeId()).isEqualTo(otherCollege);
        assertThat(membership.getRollNo()).isEqualTo("IT-007");
        assertThat(membership.getDepartment()).isEqualTo("Information Technology");
        assertThat(membership.getReason()).isNull();
        assertThat(membership.getDecidedBy()).isNull();
        assertThat(membership.getDecidedAt()).isNull();
    }

    @Test
    void reapply_notRejected_throws() {
        Membership pending = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");
        Membership approved = Membership.request(STUDENT, COLLEGE, "CS-042", "Computer Engineering");
        approved.approve(TPO, NOW);

        assertThatThrownBy(() -> pending.reapply(COLLEGE, "CS-042", "Computer Engineering"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.reapply(COLLEGE, "CS-042", "Computer Engineering"))
                .isInstanceOf(IllegalStateException.class);
    }
}
