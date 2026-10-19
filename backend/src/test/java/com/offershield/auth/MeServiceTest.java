package com.offershield.auth;

import com.offershield.college.CollegeService;
import com.offershield.college.CollegeSummary;
import com.offershield.common.web.NotFoundException;
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
    void user_noCollege() {
        AppUser student = AppUser.create("sub-student", "student@example.com", true, "Test Student");
        when(users.findById(student.getId())).thenReturn(Optional.of(student));

        MeResponse me = service.me(student.getId());

        assertThat(me.college()).isNull();
        assertThat(me.role()).isEqualTo(Role.USER);
        assertThat(me.membershipStatus()).isEqualTo("NONE");
        verifyNoInteractions(collegeService);
    }

    @Test
    void tpo_collegeRowMissing_collegeNull() {
        UUID collegeId = UUID.randomUUID();
        AppUser tpo = AppUser.create("sub-tpo", "tpo@example.com", true, "Test TPO");
        tpo.assignTpoCollege(collegeId);
        tpo.changeRole(Role.TPO);
        when(users.findById(tpo.getId())).thenReturn(Optional.of(tpo));
        when(collegeService.findSummary(collegeId)).thenReturn(Optional.empty());

        assertThat(service.me(tpo.getId()).college()).isNull();
    }
}
