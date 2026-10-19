package com.offershield.auth;

import com.offershield.common.audit.AuditLogService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a verified Google identity into our {@link AppUser} row (keyed on {@code sub}) and works out the role.
 * The role is recomputed on every login. An unverified email never becomes ADMIN and never consumes a
 * TPO assignment (R-C). Emails and names are never logged here.
 */
@Service
public class OidcUserProvisioningService {

    static final String TPO_ASSIGNMENT_CONSUMED = "TPO_ASSIGNMENT_CONSUMED";
    private static final int MAX_NAME_LENGTH = 200;

    private final AppUserRepository users;
    private final TpoAssignmentRepository assignments;
    private final AdminProperties adminProperties;
    private final AuditLogService auditLogService;
    private final RoleLookup roleLookup;
    private final TransactionOperations transactions;

    public OidcUserProvisioningService(AppUserRepository users, TpoAssignmentRepository assignments,
                                       AdminProperties adminProperties, AuditLogService auditLogService,
                                       RoleLookup roleLookup, TransactionOperations transactions) {
        this.users = users;
        this.assignments = assignments;
        this.adminProperties = adminProperties;
        this.auditLogService = auditLogService;
        this.roleLookup = roleLookup;
        this.transactions = transactions;
    }

    /**
     * Two first sign-ins with the same {@code sub} (e.g. two tabs) can both find no row and both INSERT; the loser's
     * INSERT breaks {@code uk_app_user_google_sub} at commit. Its transaction is then rolled back, so a fresh one
     * re-reads the row by {@code sub} and updates it instead. A second failure is a real error and propagates.
     */
    public ProvisionedUser provision(OidcIdentity identity) {
        try {
            return transactions.execute(status -> provisionOnce(identity));
        } catch (DataIntegrityViolationException ex) {
            return transactions.execute(status -> provisionOnce(identity));
        }
    }

    private ProvisionedUser provisionOnce(OidcIdentity identity) {
        String email = normalise(identity.email());
        String name = truncate(identity.name());

        AppUser user = users.findByGoogleSub(identity.sub())
                .orElseGet(() -> AppUser.create(identity.sub(), email, identity.emailVerified(), name));
        user.updateIdentity(email, identity.emailVerified(), name);

        Role role = resolveRole(user, email, identity.emailVerified());
        if (role != user.getRole()) {
            // The user's other signed-in sessions pick the new role up on their next request.
            roleLookup.evict(user.getId());
        }
        user.changeRole(role);
        users.save(user);
        return new ProvisionedUser(user.getId(), role);
    }

    private Role resolveRole(AppUser user, String email, boolean emailVerified) {
        if (emailVerified && adminProperties.isAdmin(email)) {
            return Role.ADMIN;
        }
        if (emailVerified && !email.isEmpty()) {
            consumeTpoAssignment(user, email);
        }
        return user.getTpoCollegeId() != null ? Role.TPO : Role.USER;
    }

    private void consumeTpoAssignment(AppUser user, String email) {
        // The column collation is accent-insensitive ("jose" finds "josé"), so insist on the exact stored email.
        Optional<TpoAssignment> assignment = assignments.findByEmail(email)
                .filter(found -> found.getEmail().equals(email));
        if (assignment.isEmpty()) {
            return;
        }
        UUID collegeId = assignment.get().getCollegeId();
        // Concurrent sign-ins can all read the row; only the one whose delete removes it becomes TPO.
        if (assignments.consume(assignment.get().getId(), collegeId) == 1) {
            user.assignTpoCollege(collegeId);
            auditLogService.record(user.getId(), TPO_ASSIGNMENT_CONSUMED, collegeId);
        }
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String name) {
        return name != null && name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }
}
