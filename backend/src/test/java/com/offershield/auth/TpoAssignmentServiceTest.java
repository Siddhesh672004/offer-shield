package com.offershield.auth;

import com.offershield.common.audit.AuditLogService;
import com.offershield.common.web.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TpoAssignmentServiceTest {

    private static final UUID COLLEGE_ID = UUID.randomUUID();
    private static final UUID OTHER_COLLEGE_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock
    private AppUserRepository users;
    @Mock
    private TpoAssignmentRepository assignments;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private RoleLookup roleLookup;

    @InjectMocks
    private TpoAssignmentService service;

    @Test
    void assign_verifiedUser_promotedNowAndAudited() {
        AppUser user = AppUser.create("sub-1", "tpo@example.com", true, "Tara TPO");
        when(users.findAllByEmail("tpo@example.com")).thenReturn(List.of(user));

        TpoAssignmentResult result = service.assign(COLLEGE_ID, " TPO@Example.com ", ADMIN_ID);

        assertThat(result).isEqualTo(new TpoAssignmentResult("tpo@example.com", TpoAssignmentStatus.ASSIGNED));
        assertThat(user.getRole()).isEqualTo(Role.TPO);
        assertThat(user.getTpoCollegeId()).isEqualTo(COLLEGE_ID);
        verify(auditLogService).record(ADMIN_ID, TpoAssignmentService.TPO_ASSIGNED, user.getId());
        verify(assignments, never()).saveAndFlush(any(TpoAssignment.class));
    }

    /** The promoted user's open sessions pick up TPO on their next request, not after the role cache expires. */
    @Test
    void assign_verifiedUser_cachedRoleEvicted() {
        AppUser user = AppUser.create("sub-9", "tpo@example.com", true, "Tara TPO");
        when(users.findAllByEmail("tpo@example.com")).thenReturn(List.of(user));

        service.assign(COLLEGE_ID, "tpo@example.com", ADMIN_ID);

        verify(roleLookup).evict(user.getId());
    }

    @Test
    void assign_verifiedAdmin_staysAdminWithCollege() {
        AppUser admin = AppUser.create("sub-2", "admin@example.com", true, "Ada Admin");
        admin.changeRole(Role.ADMIN);
        when(users.findAllByEmail("admin@example.com")).thenReturn(List.of(admin));

        TpoAssignmentResult result = service.assign(COLLEGE_ID, "admin@example.com", ADMIN_ID);

        assertThat(result.status()).isEqualTo(TpoAssignmentStatus.ASSIGNED);
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getTpoCollegeId()).isEqualTo(COLLEGE_ID);
    }

    @Test
    void assign_existingTpoOfOtherCollege_movedToNewCollege() {
        AppUser tpo = AppUser.create("sub-3", "tpo@example.com", true, "Tara TPO");
        tpo.assignTpoCollege(OTHER_COLLEGE_ID);
        tpo.changeRole(Role.TPO);
        when(users.findAllByEmail("tpo@example.com")).thenReturn(List.of(tpo));

        service.assign(COLLEGE_ID, "tpo@example.com", ADMIN_ID);

        assertThat(tpo.getTpoCollegeId()).isEqualTo(COLLEGE_ID);
        assertThat(tpo.getRole()).isEqualTo(Role.TPO);
    }

    @Test
    void assign_noVerifiedUser_pendingAssignmentCreatedAndAudited() {
        when(users.findAllByEmail("new.tpo@example.com")).thenReturn(List.of());
        when(assignments.findByEmail("new.tpo@example.com")).thenReturn(Optional.empty());

        TpoAssignmentResult result = service.assign(COLLEGE_ID, "  New.TPO@Example.COM ", ADMIN_ID);

        assertThat(result).isEqualTo(new TpoAssignmentResult("new.tpo@example.com", TpoAssignmentStatus.PENDING));
        ArgumentCaptor<TpoAssignment> saved = ArgumentCaptor.forClass(TpoAssignment.class);
        verify(assignments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("new.tpo@example.com");
        assertThat(saved.getValue().getCollegeId()).isEqualTo(COLLEGE_ID);
        assertThat(saved.getValue().getAssignedBy()).isEqualTo(ADMIN_ID);
        verify(auditLogService).record(ADMIN_ID, TpoAssignmentService.TPO_ASSIGNMENT_CREATED, COLLEGE_ID);
    }

    @Test
    void assign_unverifiedUserOnly_neverPromoted() {
        AppUser unverified = AppUser.create("sub-4", "maybe@example.com", false, "Unverified Person");
        when(users.findAllByEmail("maybe@example.com")).thenReturn(List.of(unverified));
        when(assignments.findByEmail("maybe@example.com")).thenReturn(Optional.empty());

        TpoAssignmentResult result = service.assign(COLLEGE_ID, "maybe@example.com", ADMIN_ID);

        assertThat(result.status()).isEqualTo(TpoAssignmentStatus.PENDING);
        assertThat(unverified.getRole()).isEqualTo(Role.USER);
        assertThat(unverified.getTpoCollegeId()).isNull();
        verify(assignments).saveAndFlush(any(TpoAssignment.class));
    }

    @Test
    void assign_accentVariantEmailMatch_notPromoted() {
        // The column collation is accent-insensitive; only an exact match may be promoted.
        AppUser lookalike = AppUser.create("sub-5", "josé@example.com", true, "Lookalike");
        when(users.findAllByEmail("jose@example.com")).thenReturn(List.of(lookalike));
        when(assignments.findByEmail("jose@example.com")).thenReturn(Optional.empty());

        TpoAssignmentResult result = service.assign(COLLEGE_ID, "jose@example.com", ADMIN_ID);

        assertThat(result.status()).isEqualTo(TpoAssignmentStatus.PENDING);
        assertThat(lookalike.getRole()).isEqualTo(Role.USER);
        assertThat(lookalike.getTpoCollegeId()).isNull();
    }

    @Test
    void assign_existingPendingAssignment_replacedBeforeInsert() {
        TpoAssignment old = TpoAssignment.create("new.tpo@example.com", OTHER_COLLEGE_ID, UUID.randomUUID());
        when(users.findAllByEmail("new.tpo@example.com")).thenReturn(List.of());
        when(assignments.findByEmail("new.tpo@example.com")).thenReturn(Optional.of(old));

        TpoAssignmentResult result = service.assign(COLLEGE_ID, "new.tpo@example.com", ADMIN_ID);

        assertThat(result.status()).isEqualTo(TpoAssignmentStatus.PENDING);
        InOrder order = inOrder(assignments);
        order.verify(assignments).delete(old);
        order.verify(assignments).flush();
        ArgumentCaptor<TpoAssignment> saved = ArgumentCaptor.forClass(TpoAssignment.class);
        order.verify(assignments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCollegeId()).isEqualTo(COLLEGE_ID);
        assertThat(saved.getValue().getAssignedBy()).isEqualTo(ADMIN_ID);
    }

    /** Two admins assigning the same new email at once: the unique key lets one row in; the other gets a clean 409. */
    @Test
    void assign_concurrentInsertOfSameEmail_409NotAudited() {
        when(users.findAllByEmail("new.tpo@example.com")).thenReturn(List.of());
        when(assignments.findByEmail("new.tpo@example.com")).thenReturn(Optional.empty());
        when(assignments.saveAndFlush(any(TpoAssignment.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.assign(COLLEGE_ID, "new.tpo@example.com", ADMIN_ID))
                .isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.status().value()).isEqualTo(409));
        verify(auditLogService, never()).record(any(), any(), any());
    }

    @Test
    void assign_pendingRowRemovedConcurrently_409NotAudited() {
        TpoAssignment old = TpoAssignment.create("new.tpo@example.com", OTHER_COLLEGE_ID, UUID.randomUUID());
        when(users.findAllByEmail("new.tpo@example.com")).thenReturn(List.of());
        when(assignments.findByEmail("new.tpo@example.com")).thenReturn(Optional.of(old));
        doThrow(new ObjectOptimisticLockingFailureException(TpoAssignment.class, old.getId())).when(assignments).flush();

        assertThatThrownBy(() -> service.assign(COLLEGE_ID, "new.tpo@example.com", ADMIN_ID))
                .isInstanceOf(ConflictException.class);
        verify(assignments, never()).saveAndFlush(any(TpoAssignment.class));
        verify(auditLogService, never()).record(any(), any(), any());
    }

    /** The email key is accent-insensitive: "jose" finds the pending "josé" row, which must not be silently replaced. */
    @Test
    void assign_pendingRowForAccentVariantEmail_409RowKept() {
        TpoAssignment accented = TpoAssignment.create("josé@example.com", OTHER_COLLEGE_ID, UUID.randomUUID());
        when(users.findAllByEmail("jose@example.com")).thenReturn(List.of());
        when(assignments.findByEmail("jose@example.com")).thenReturn(Optional.of(accented));

        assertThatThrownBy(() -> service.assign(COLLEGE_ID, "jose@example.com", ADMIN_ID))
                .isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.getMessage())
                        .contains("differs only in accents")
                        .doesNotContain("jose"));
        verify(assignments, never()).delete(any(TpoAssignment.class));
        verify(assignments, never()).saveAndFlush(any(TpoAssignment.class));
        verify(auditLogService, never()).record(any(), any(), any());
    }

    /** Otherwise the leftover row would move the new TPO to its (older) college at their next sign-in. */
    @Test
    void assign_verifiedUserWithPendingRowForSameEmail_pendingRowDeleted() {
        AppUser user = AppUser.create("sub-10", "tpo@example.com", true, "Tara TPO");
        TpoAssignment leftover = TpoAssignment.create("tpo@example.com", OTHER_COLLEGE_ID, UUID.randomUUID());
        when(users.findAllByEmail("tpo@example.com")).thenReturn(List.of(user));
        when(assignments.findByEmail("tpo@example.com")).thenReturn(Optional.of(leftover));

        TpoAssignmentResult result = service.assign(COLLEGE_ID, "tpo@example.com", ADMIN_ID);

        assertThat(result.status()).isEqualTo(TpoAssignmentStatus.ASSIGNED);
        assertThat(user.getTpoCollegeId()).isEqualTo(COLLEGE_ID);
        verify(assignments).delete(leftover);
    }

    @Test
    void assign_verifiedUserWithPendingRowForAccentVariant_rowKept() {
        AppUser user = AppUser.create("sub-11", "jose@example.com", true, "Jose TPO");
        TpoAssignment accented = TpoAssignment.create("josé@example.com", OTHER_COLLEGE_ID, UUID.randomUUID());
        when(users.findAllByEmail("jose@example.com")).thenReturn(List.of(user));
        when(assignments.findByEmail("jose@example.com")).thenReturn(Optional.of(accented));

        service.assign(COLLEGE_ID, "jose@example.com", ADMIN_ID);

        assertThat(user.getTpoCollegeId()).isEqualTo(COLLEGE_ID);
        verify(assignments, never()).delete(any(TpoAssignment.class));
    }

    @Test
    void findTpoEmailsByCollege_groupsTposAndPendingSorted() {
        AppUser tpoB = AppUser.create("sub-6", "b.tpo@example.com", true, "B");
        tpoB.assignTpoCollege(COLLEGE_ID);
        AppUser tpoA = AppUser.create("sub-7", "a.tpo@example.com", true, "A");
        tpoA.assignTpoCollege(COLLEGE_ID);
        AppUser otherTpo = AppUser.create("sub-8", "other.tpo@example.com", true, "O");
        otherTpo.assignTpoCollege(OTHER_COLLEGE_ID);
        when(users.findByTpoCollegeIdIsNotNull()).thenReturn(List.of(tpoB, tpoA, otherTpo));
        when(assignments.findAll()).thenReturn(List.of(
                TpoAssignment.create("z.pending@example.com", COLLEGE_ID, ADMIN_ID),
                TpoAssignment.create("y.pending@example.com", COLLEGE_ID, ADMIN_ID)));

        Map<UUID, CollegeTpoEmails> roster = service.findTpoEmailsByCollege();

        assertThat(roster.get(COLLEGE_ID)).isEqualTo(new CollegeTpoEmails(
                List.of("a.tpo@example.com", "b.tpo@example.com"),
                List.of("y.pending@example.com", "z.pending@example.com")));
        assertThat(roster.get(OTHER_COLLEGE_ID))
                .isEqualTo(new CollegeTpoEmails(List.of("other.tpo@example.com"), List.of()));
    }
}
