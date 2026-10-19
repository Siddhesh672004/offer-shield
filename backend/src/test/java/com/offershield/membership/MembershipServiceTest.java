package com.offershield.membership;

import com.offershield.auth.UserContact;
import com.offershield.auth.UserDirectory;
import com.offershield.college.CollegeService;
import com.offershield.college.CollegeSummary;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.web.ApiException;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.ForbiddenException;
import com.offershield.notification.EmailRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Branches that are hard to reach over HTTP: races and a session role that no longer matches the database. */
@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    @Mock
    private MembershipRepository memberships;
    @Mock
    private UserDirectory userDirectory;
    @Mock
    private CollegeService collegeService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private MembershipService service;

    private final UUID studentId = UUID.randomUUID();
    private final UUID collegeId = UUID.randomUUID();
    private final UUID tpoId = UUID.randomUUID();

    @Test
    void request_concurrentDuplicateInsert_409MembershipExists() {
        when(userDirectory.findContact(studentId))
                .thenReturn(Optional.of(new UserContact(studentId, "fake.student@example.com", "Fake Student", true)));
        when(collegeService.findActiveSummary(collegeId)).thenReturn(Optional.of(new CollegeSummary(collegeId, "Fake College")));
        when(memberships.findByUserId(studentId)).thenReturn(Optional.empty());
        when(memberships.saveAndFlush(any(Membership.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.request(studentId, new CreateMembershipRequest(collegeId, "CS-1", "Civil")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status().value()).isEqualTo(409);
                    assertThat(ex.errorCode()).isEqualTo("MEMBERSHIP_EXISTS");
                });
    }

    @Test
    void decide_sessionSaysTpoButNoCollegeInDb_403() {
        when(userDirectory.findTpoCollegeId(tpoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(tpoId, UUID.randomUUID(), 0L,
                new MembershipDecisionRequest(MembershipDecision.APPROVE, null)))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(memberships, auditLogService, events);
    }

    @Test
    void decide_concurrentDecisionAtFlush_409NoEmail() {
        Membership membership = Membership.request(studentId, collegeId, "CS-1", "Civil");
        ReflectionTestUtils.setField(membership, "version", 0L);
        when(userDirectory.findTpoCollegeId(tpoId)).thenReturn(Optional.of(collegeId));
        when(memberships.findByIdAndCollegeId(membership.getId(), collegeId)).thenReturn(Optional.of(membership));
        when(memberships.saveAndFlush(membership))
                .thenThrow(new ObjectOptimisticLockingFailureException(Membership.class, membership.getId()));

        assertThatThrownBy(() -> service.decide(tpoId, membership.getId(), 0L,
                new MembershipDecisionRequest(MembershipDecision.APPROVE, null)))
                .isInstanceOf(ConflictException.class);
        verify(memberships).saveAndFlush(membership);
        verify(auditLogService, never()).record(any(), any(), any());
        verify(events, never()).publishEvent(any(EmailRequested.class));
    }
}
