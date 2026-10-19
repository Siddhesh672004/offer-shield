package com.offershield.auth;

import com.offershield.common.audit.AuditLogService;
import com.offershield.common.web.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin TPO assignment (P3-01), owned by the auth feature so other features never touch auth repositories.
 * A verified user with the exact email is promoted now; otherwise a {@link TpoAssignment} waits for that
 * email's first verified sign-in. Unverified users are never promoted (R-C), and an ADMIN keeps the ADMIN role.
 * Emails are never logged here.
 */
@Service
@Transactional
public class TpoAssignmentService {

    static final String TPO_ASSIGNED = "TPO_ASSIGNED";
    static final String TPO_ASSIGNMENT_CREATED = "TPO_ASSIGNMENT_CREATED";
    private static final String ACCENT_VARIANT_PENDING = "A pending TPO assignment exists for an address that differs "
            + "only in accents from this one. Check the exact address with the TPO and try again.";
    private static final String CONCURRENT_CHANGE =
            "This TPO assignment was changed at the same time by someone else. Reload and try again.";

    private final AppUserRepository users;
    private final TpoAssignmentRepository assignments;
    private final AuditLogService auditLogService;
    private final RoleLookup roleLookup;

    public TpoAssignmentService(AppUserRepository users, TpoAssignmentRepository assignments,
                                AuditLogService auditLogService, RoleLookup roleLookup) {
        this.users = users;
        this.assignments = assignments;
        this.auditLogService = auditLogService;
        this.roleLookup = roleLookup;
    }

    /** The caller has already checked that {@code collegeId} exists. */
    public TpoAssignmentResult assign(UUID collegeId, String email, UUID adminId) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);

        // The email column's collation is case- and accent-insensitive, so require an exact match.
        List<AppUser> verifiedUsers = users.findAllByEmail(normalised).stream()
                .filter(AppUser::isEmailVerified)
                .filter(user -> user.getEmail().equals(normalised))
                .toList();

        if (!verifiedUsers.isEmpty()) {
            verifiedUsers.forEach(user -> promote(user, collegeId, adminId));
            // A leftover pending row for this exact email would move them to its (older) college at next sign-in.
            findExactPending(normalised).ifPresent(assignments::delete);
            return new TpoAssignmentResult(normalised, TpoAssignmentStatus.ASSIGNED);
        }

        upsertPendingAssignment(normalised, collegeId, adminId);
        auditLogService.record(adminId, TPO_ASSIGNMENT_CREATED, collegeId);
        return new TpoAssignmentResult(normalised, TpoAssignmentStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Map<UUID, CollegeTpoEmails> findTpoEmailsByCollege() {
        Map<UUID, List<String>> tpos = sortedEmailsByCollege(users.findByTpoCollegeIdIsNotNull(),
                AppUser::getTpoCollegeId, AppUser::getEmail);
        Map<UUID, List<String>> pending = sortedEmailsByCollege(assignments.findAll(),
                TpoAssignment::getCollegeId, TpoAssignment::getEmail);

        Set<UUID> collegeIds = new HashSet<>(tpos.keySet());
        collegeIds.addAll(pending.keySet());
        return collegeIds.stream().collect(Collectors.toMap(Function.identity(), id -> new CollegeTpoEmails(
                tpos.getOrDefault(id, List.of()), pending.getOrDefault(id, List.of()))));
    }

    private void promote(AppUser user, UUID collegeId, UUID adminId) {
        user.assignTpoCollege(collegeId);
        if (user.getRole() != Role.ADMIN) {
            user.changeRole(Role.TPO);
        }
        // Already signed in? The session gets the new role on its next request (SessionRoleRefreshFilter).
        roleLookup.evict(user.getId());
        auditLogService.record(adminId, TPO_ASSIGNED, user.getId());
    }

    /**
     * One pending row per email (unique key). Re-assigning replaces the old row; the delete is flushed first
     * because Hibernate runs inserts before deletes, which would trip the unique key. The key is accent-insensitive,
     * so a row for an address that differs only in accents blocks this one: refuse (409) rather than silently
     * replacing another person's assignment. A concurrent admin changing the same row also gets 409.
     */
    private void upsertPendingAssignment(String email, UUID collegeId, UUID adminId) {
        Optional<TpoAssignment> existing = assignments.findByEmail(email);
        if (existing.isPresent() && !existing.get().getEmail().equals(email)) {
            throw new ConflictException(ACCENT_VARIANT_PENDING);
        }
        try {
            if (existing.isPresent()) {
                assignments.delete(existing.get());
                assignments.flush();
            }
            assignments.saveAndFlush(TpoAssignment.create(email, collegeId, adminId));
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException ex) {
            throw new ConflictException(CONCURRENT_CHANGE);
        }
    }

    /** The pending row for exactly this email; the column's collation also matches accent variants. */
    private Optional<TpoAssignment> findExactPending(String email) {
        return assignments.findByEmail(email).filter(assignment -> assignment.getEmail().equals(email));
    }

    private static <T> Map<UUID, List<String>> sortedEmailsByCollege(List<T> rows, Function<T, UUID> college,
                                                                     Function<T, String> email) {
        return rows.stream().collect(Collectors.groupingBy(college,
                Collectors.mapping(email, Collectors.collectingAndThen(Collectors.toList(),
                        list -> list.stream().distinct().sorted().toList()))));
    }
}
