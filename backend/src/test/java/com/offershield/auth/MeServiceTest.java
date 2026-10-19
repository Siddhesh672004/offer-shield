package com.offershield.auth;

import com.offershield.college.CollegeService;
import com.offershield.college.CollegeSummary;
import com.offershield.common.web.NotFoundException;
import com.offershield.membership.MembershipResponse;
import com.offershield.membership.MembershipService;
import com.offershield.membership.MembershipStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeServiceTest {

    @Mock
    private AppUserRepository users;
    @Mock
    private CollegeService collegeService;
    @Mock
    private MembershipService membershipService;

    @InjectMocks
    private MeService service;

    @Test
    void tpo_returnsCollege() {
        UUID collegeId = UUID.randomUUID();
        AppUser tpo = AppUser.create("sub-tpo", "tpo@example.com", true, "Test TPO");
        tpo.assignTpoCollege(collegeId);
        tpo.changeRole(Role.TPO);
        CollegeSummary college = new CollegeSummary(collegeId, "Example Institute of Technology");
        when(users.findById(tpo.getId())).thenReturn(Optional.of(tpo));
        when(collegeService.findSummary(collegeId)).thenReturn(Optional.of(college));
        when(membershipService.findForUser(tpo.getId())).thenReturn(Optional.empty());

        MeResponse me = service.me(tpo.getId());

        assertThat(me).isEqualTo(new MeResponse(tpo.getId(), "Test TPO", "tpo@example.com", Role.TPO, college, "NONE"));
    }

    @Test
    void unknownUser_notFound() {
        UUID unknown = UUID.randomUUID();
        when(users.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(unknown))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void user_noMembership_noCollegeStatusNone() {
        AppUser student = AppUser.create("sub-student", "student@example.com", true, "Test Student");
        when(users.findById(student.getId())).thenReturn(Optional.of(student));
        when(membershipService.findForUser(student.getId())).thenReturn(Optional.empty());

        MeResponse me = service.me(student.getId());

        assertThat(me.college()).isNull();
        assertThat(me.role()).isEqualTo(Role.USER);
        assertThat(me.membershipStatus()).isEqualTo("NONE");
        verifyNoInteractions(collegeService);
    }

    @Test
    void user_pendingMembership_collegeAndStatusFromMembership() {
        AppUser student = AppUser.create("sub-student-2", "student2@example.com", true, "Test Student");
        UUID collegeId = UUID.randomUUID();
        when(users.findById(student.getId())).thenReturn(Optional.of(student));
        when(membershipService.findForUser(student.getId())).thenReturn(Optional.of(membership(collegeId, MembershipStatus.PENDING)));

        MeResponse me = service.me(student.getId());

        assertThat(me.college()).isEqualTo(new CollegeSummary(collegeId, "Fake College of Engineering"));
        assertThat(me.membershipStatus()).isEqualTo("PENDING");
        verifyNoInteractions(collegeService);
    }

    @Test
    void user_rejectedMembership_collegeStillShown() {
        AppUser student = AppUser.create("sub-student-3", "student3@example.com", true, "Test Student");
        UUID collegeId = UUID.randomUUID();
        when(users.findById(student.getId())).thenReturn(Optional.of(student));
        when(membershipService.findForUser(student.getId())).thenReturn(Optional.of(membership(collegeId, MembershipStatus.REJECTED)));

        MeResponse me = service.me(student.getId());

        assertThat(me.college()).isEqualTo(new CollegeSummary(collegeId, "Fake College of Engineering"));
        assertThat(me.membershipStatus()).isEqualTo("REJECTED");
    }

    @Test
    void tpo_collegeRowMissing_collegeNull() {
        UUID collegeId = UUID.randomUUID();
        AppUser tpo = AppUser.create("sub-tpo", "tpo@example.com", true, "Test TPO");
        tpo.assignTpoCollege(collegeId);
        tpo.changeRole(Role.TPO);
        when(users.findById(tpo.getId())).thenReturn(Optional.of(tpo));
        when(collegeService.findSummary(collegeId)).thenReturn(Optional.empty());
        when(membershipService.findForUser(tpo.getId())).thenReturn(Optional.empty());

        assertThat(service.me(tpo.getId()).college()).isNull();
    }

    private static MembershipResponse membership(UUID collegeId, MembershipStatus status) {
        return new MembershipResponse(UUID.randomUUID(), collegeId, "Fake College of Engineering", "CS-1", "Civil",
                status, status == MembershipStatus.REJECTED ? "Not on the list" : null, 1L);
    }
}
