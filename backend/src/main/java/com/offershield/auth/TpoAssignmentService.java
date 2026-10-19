package com.offershield.auth;

import com.offershield.common.audit.AuditLogService;
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

    private final AppUserRepository users;
    private final TpoAssignmentRepository assignments;
    private final AuditLogService auditLogService;

    public TpoAssignmentService(AppUserRepository users, TpoAssignmentRepository assignments,
                                AuditLogService auditLogService) {
        this.users = users;
        this.assignments = assignments;
        this.auditLogService = auditLogService;
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
        auditLogService.record(adminId, TPO_ASSIGNED, user.getId());
    }

    /**
     * One pending row per email (unique key). Re-assigning replaces the old row; the delete is flushed first
     * because Hibernate runs inserts before deletes, which would trip the unique key.
     */
    private void upsertPendingAssignment(String email, UUID collegeId, UUID adminId) {
        Optional<TpoAssignment> existing = assignments.findByEmail(email);
        if (existing.isPresent()) {
            assignments.delete(existing.get());
            assignments.flush();
        }
        assignments.save(TpoAssignment.create(email, collegeId, adminId));
    }

    private static <T> Map<UUID, List<String>> sortedEmailsByCollege(List<T> rows, Function<T, UUID> college,
                                                                     Function<T, String> email) {
        return rows.stream().collect(Collectors.groupingBy(college,
                Collectors.mapping(email, Collectors.collectingAndThen(Collectors.toList(),
                        list -> list.stream().distinct().sorted().toList()))));
    }
}
