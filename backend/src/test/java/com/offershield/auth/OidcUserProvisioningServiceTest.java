package com.offershield.auth;

import com.offershield.common.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OidcUserProvisioningServiceTest {

    private static final String SUB = "google-sub-1";
    private static final UUID COLLEGE_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock
    private AppUserRepository users;
    @Mock
    private TpoAssignmentRepository assignments;
    @Mock
    private AuditLogService auditLogService;

    private OidcUserProvisioningService service;

    @BeforeEach
    void setUp() {
        service = provisioningWithAdmins(List.of("admin@example.com"));
        lenient().when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private OidcUserProvisioningService provisioningWithAdmins(List<String> adminEmails) {
        return new OidcUserProvisioningService(users, assignments, new AdminProperties(adminEmails), auditLogService,
                TransactionOperations.withoutTransaction());
    }

    /** Two first sign-ins with one sub: the loser's INSERT hits uk_app_user_google_sub, so it re-reads and updates. */
    @Test
    void firstLoginRace_duplicateSubOnInsert_rereadsBySubAndUpdatesThatRow() {
        AppUser winner = AppUser.create(SUB, "student@example.com", true, "Test Student");
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty(), Optional.of(winner));
        when(users.save(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate sub"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "student@example.com", true, "Test Student"));

        assertThat(result.userId()).isEqualTo(winner.getId());
        assertThat(result.role()).isEqualTo(Role.USER);
        verify(users, times(2)).findByGoogleSub(SUB);
        verify(users).save(winner);
    }

    @Test
    void firstLoginRace_retryAlsoViolatesConstraint_exceptionPropagates() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());
        when(users.save(any(AppUser.class))).thenThrow(new DataIntegrityViolationException("still failing"));

        assertThatThrownBy(() -> service.provision(new OidcIdentity(SUB, "student@example.com", true, "Test Student")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(users, times(2)).save(any(AppUser.class));
    }

    @Test
    void newSub_createsUser() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "student@example.com", true, "Test Student"));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getGoogleSub()).isEqualTo(SUB);
        assertThat(saved.getValue().getEmail()).isEqualTo("student@example.com");
        assertThat(saved.getValue().isEmailVerified()).isTrue();
        assertThat(saved.getValue().getName()).isEqualTo("Test Student");
        assertThat(result.userId()).isEqualTo(saved.getValue().getId()).isNotNull();
        assertThat(result.role()).isEqualTo(Role.USER);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void existingSub_emailChange_sameRow() {
        AppUser existing = AppUser.create(SUB, "old@example.com", true, "Old Name");
        UUID originalId = existing.getId();
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.of(existing));

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "new@example.com", false, "New Name"));

        assertThat(result.userId()).isEqualTo(originalId);
        assertThat(existing.getId()).isEqualTo(originalId);
        assertThat(existing.getEmail()).isEqualTo("new@example.com");
        assertThat(existing.isEmailVerified()).isFalse();
        assertThat(existing.getName()).isEqualTo("New Name");
        verify(users).save(existing);
    }

    @Test
    void verifiedAdminEmail_admin() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "admin@example.com", true, "Test Admin"));

        assertThat(result.role()).isEqualTo(Role.ADMIN);
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void verifiedAdminEmail_caseInsensitive_admin() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, " Admin@Example.com ", true, "Test Admin"));

        assertThat(result.role()).isEqualTo(Role.ADMIN);
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void verifiedTpoAssignment_becomesTpoAndAuditWritten() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());
        TpoAssignment assignment = TpoAssignment.create("tpo@example.com", COLLEGE_ID, ADMIN_ID);
        when(assignments.findByEmail("tpo@example.com")).thenReturn(Optional.of(assignment));
        when(assignments.consume(assignment.getId(), COLLEGE_ID)).thenReturn(1);

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, " TPO@Example.com", true, "Test TPO"));

        assertThat(result.role()).isEqualTo(Role.TPO);
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.TPO);
        assertThat(saved.getValue().getTpoCollegeId()).isEqualTo(COLLEGE_ID);
        verify(assignments).consume(assignment.getId(), COLLEGE_ID);
        verify(assignments, never()).delete(any(TpoAssignment.class));
        verify(auditLogService).record(result.userId(), "TPO_ASSIGNMENT_CONSUMED", COLLEGE_ID);
    }

    /** Another sign-in deleted the row between our read and our delete: only the winner (1 row) becomes TPO. */
    @Test
    void tpoAssignmentConsumedByConcurrentLogin_staysUserWithoutAudit() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());
        TpoAssignment assignment = TpoAssignment.create("tpo@example.com", COLLEGE_ID, ADMIN_ID);
        when(assignments.findByEmail("tpo@example.com")).thenReturn(Optional.of(assignment));
        when(assignments.consume(assignment.getId(), COLLEGE_ID)).thenReturn(0);

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "tpo@example.com", true, "Test TPO"));

        assertThat(result.role()).isEqualTo(Role.USER);
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getTpoCollegeId()).isNull();
        verifyNoInteractions(auditLogService);
    }

    /** MySQL's utf8mb4_0900_ai_ci collation finds "josé" when asked for "jose"; only an exact match may grant TPO. */
    @Test
    void assignmentEmailMatchesOnlyThroughCollation_notConsumed() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());
        TpoAssignment accented = TpoAssignment.create("josé.tpo@example.com", COLLEGE_ID, ADMIN_ID);
        when(assignments.findByEmail("jose.tpo@example.com")).thenReturn(Optional.of(accented));

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "jose.tpo@example.com", true, "Test Person"));

        assertThat(result.role()).isEqualTo(Role.USER);
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getTpoCollegeId()).isNull();
        verify(assignments, never()).consume(any(), any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void unverifiedEmail_userOnly() {
        service = provisioningWithAdmins(List.of("both@example.com"));
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());
        TpoAssignment assignment = TpoAssignment.create("both@example.com", COLLEGE_ID, ADMIN_ID);
        lenient().when(assignments.findByEmail(anyString())).thenReturn(Optional.of(assignment));

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "both@example.com", false, "Test Person"));

        assertThat(result.role()).isEqualTo(Role.USER);
        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(saved.getValue().getTpoCollegeId()).isNull();
        verify(assignments, never()).consume(any(), any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void existingTpo_noAssignmentLeft_staysTpoWithoutNewAudit() {
        AppUser tpo = AppUser.create(SUB, "tpo@example.com", true, "Test TPO");
        tpo.assignTpoCollege(COLLEGE_ID);
        tpo.changeRole(Role.TPO);
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.of(tpo));
        when(assignments.findByEmail("tpo@example.com")).thenReturn(Optional.empty());

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "tpo@example.com", true, "Test TPO"));

        assertThat(result.role()).isEqualTo(Role.TPO);
        assertThat(tpo.getTpoCollegeId()).isEqualTo(COLLEGE_ID);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void existingTpo_emailUnverifiedOnLaterLogin_staysTpo() {
        AppUser tpo = AppUser.create(SUB, "tpo@example.com", true, "Test TPO");
        tpo.assignTpoCollege(COLLEGE_ID);
        tpo.changeRole(Role.TPO);
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.of(tpo));

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "tpo@example.com", false, "Test TPO"));

        assertThat(result.role()).isEqualTo(Role.TPO);
        verify(assignments, never()).findByEmail(anyString());
    }

    @Test
    void removedFromAdminList_existingAdminBecomesUserAgain() {
        service = provisioningWithAdmins(List.of());
        AppUser formerAdmin = AppUser.create(SUB, "admin@example.com", true, "Test Admin");
        formerAdmin.changeRole(Role.ADMIN);
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.of(formerAdmin));
        when(assignments.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "admin@example.com", true, "Test Admin"));

        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(formerAdmin.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void verifiedAdminWithPendingAssignment_adminWinsAndAssignmentKept() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());

        ProvisionedUser result = service.provision(new OidcIdentity(SUB, "admin@example.com", true, "Test Admin"));

        assertThat(result.role()).isEqualTo(Role.ADMIN);
        verify(assignments, never()).consume(any(), any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void nameLongerThanColumn_truncatedTo200() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());

        service.provision(new OidcIdentity(SUB, "student@example.com", true, "x".repeat(250)));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getName()).hasSize(200);
    }

    @Test
    void nullName_storedAsNull() {
        when(users.findByGoogleSub(SUB)).thenReturn(Optional.empty());

        service.provision(new OidcIdentity(SUB, "student@example.com", true, null));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getName()).isNull();
    }
}
