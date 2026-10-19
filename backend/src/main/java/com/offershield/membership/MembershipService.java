package com.offershield.membership;

import com.offershield.auth.UserContact;
import com.offershield.auth.UserDirectory;
import com.offershield.college.CollegeService;
import com.offershield.college.CollegeSummary;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.web.BadRequestException;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.ForbiddenException;
import com.offershield.common.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Join requests and TPO decisions (P3-02). Identity and the TPO's college always come from the session user ID and
 * the database, never from the request. Another college's request is reported as absent (404, R-D). Each decision
 * is audited in the same transaction (R-E) and emails the student only after commit (D-22).
 */
@Service
@Transactional(readOnly = true)
public class MembershipService {

    static final String MEMBERSHIP_APPROVED = "MEMBERSHIP_APPROVED";
    static final String MEMBERSHIP_REJECTED = "MEMBERSHIP_REJECTED";

    private final MembershipRepository memberships;
    private final UserDirectory userDirectory;
    private final CollegeService collegeService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher events;

    public MembershipService(MembershipRepository memberships, UserDirectory userDirectory, CollegeService collegeService,
                             AuditLogService auditLogService, ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.userDirectory = userDirectory;
        this.collegeService = collegeService;
        this.auditLogService = auditLogService;
        this.events = events;
    }

    /** none → PENDING, or REJECTED → PENDING on the same row (D-33.3). PENDING/APPROVED → 409. */
    @Transactional
    public MembershipResponse request(UUID userId, CreateMembershipRequest request) {
        UserContact student = userDirectory.findContact(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!student.emailVerified()) {
            throw new ForbiddenException("EMAIL_NOT_VERIFIED",
                    "Your Google email address is not confirmed by Google, so you can't join a college.");
        }
        CollegeSummary college = collegeService.findActiveSummary(request.collegeId())
                .orElseThrow(() -> new NotFoundException("College not found"));
        Optional<Membership> existing = memberships.findByUserId(userId);
        Membership membership;
        if (existing.isEmpty()) {
            membership = Membership.request(userId, college.id(), request.rollNo(), request.department());
        } else if (existing.get().getStatus() == MembershipStatus.REJECTED) {
            membership = existing.get();
            membership.reapply(college.id(), request.rollNo(), request.department());
        } else {
            throw new MembershipExistsException();
        }
        try {
            memberships.saveAndFlush(membership);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent first requests: UNIQUE(user_id) lets only one row in.
            throw new MembershipExistsException();
        } catch (OptimisticLockingFailureException ex) {
            throw new MembershipExistsException();
        }
        return MembershipResponse.from(membership, college.name());
    }

    public MembershipResponse getMine(UUID userId) {
        return findForUser(userId).orElseThrow(() -> new NotFoundException("No college request yet"));
    }

    /** The user's own request (any status), for GET /api/memberships/me and /api/auth/me. */
    public Optional<MembershipResponse> findForUser(UUID userId) {
        return memberships.findByUserId(userId).map(membership -> MembershipResponse.from(membership, collegeName(membership)));
    }

    /** The TPO's own college queue, oldest request first. Student contacts come from one query for the whole list. */
    public List<PendingMembershipResponse> listPending(UUID tpoUserId) {
        UUID collegeId = requireTpoCollege(tpoUserId);
        List<Membership> pending = memberships.findPending(collegeId);
        Map<UUID, UserContact> students = userDirectory.findContacts(pending.stream().map(Membership::getUserId).toList());
        return pending.stream()
                .map(membership -> PendingMembershipResponse.from(membership, studentFrom(students, membership)))
                .toList();
    }

    /** PENDING → APPROVED / REJECTED by the TPO of that college, only at the version the TPO looked at. */
    @Transactional
    public MembershipResponse decide(UUID tpoUserId, UUID membershipId, long expectedVersion,
                                     MembershipDecisionRequest request) {
        String reason = request.reason() == null ? null : request.reason().trim();
        if (request.decision() == MembershipDecision.REJECT && (reason == null || reason.isEmpty())) {
            throw new BadRequestException("REASON_REQUIRED", "A reason is required to reject a request.");
        }
        UUID collegeId = requireTpoCollege(tpoUserId);
        Membership membership = memberships.findByIdAndCollegeId(membershipId, collegeId)
                .orElseThrow(() -> new NotFoundException("Membership not found"));
        if (membership.getVersion() == null || membership.getVersion() != expectedVersion) {
            throw new ConflictException("This request was changed by someone else. Reload and try again.");
        }
        if (!membership.isPending()) {
            throw new ConflictException("This request has already been decided.");
        }

        Instant now = Instant.now();
        if (request.decision() == MembershipDecision.APPROVE) {
            membership.approve(tpoUserId, now);
        } else {
            membership.reject(tpoUserId, reason, now);
        }
        try {
            memberships.saveAndFlush(membership);
        } catch (OptimisticLockingFailureException ex) {
            throw new ConflictException("This request was changed by someone else. Reload and try again.");
        }
        boolean approved = membership.getStatus() == MembershipStatus.APPROVED;
        auditLogService.record(tpoUserId, approved ? MEMBERSHIP_APPROVED : MEMBERSHIP_REJECTED, membership.getId());

        String collegeName = collegeName(membership);
        events.publishEvent(MembershipEmails.decided(student(membership).email(), collegeName,
                membership.getStatus(), membership.getReason()));
        return MembershipResponse.from(membership, collegeName);
    }

    /** The college comes from the database, so a stale session role can't act for a college (403). */
    private UUID requireTpoCollege(UUID tpoUserId) {
        return userDirectory.findTpoCollegeId(tpoUserId)
                .orElseThrow(() -> new ForbiddenException("FORBIDDEN", "You do not have permission to do this."));
    }

    private UserContact student(Membership membership) {
        return userDirectory.findContact(membership.getUserId())
                .orElseThrow(() -> new IllegalStateException("Membership without a user row"));
    }

    private static UserContact studentFrom(Map<UUID, UserContact> students, Membership membership) {
        UserContact student = students.get(membership.getUserId());
        if (student == null) {
            throw new IllegalStateException("Membership without a user row");
        }
        return student;
    }

    private String collegeName(Membership membership) {
        return collegeService.findSummary(membership.getCollegeId()).map(CollegeSummary::name).orElse(null);
    }
}
