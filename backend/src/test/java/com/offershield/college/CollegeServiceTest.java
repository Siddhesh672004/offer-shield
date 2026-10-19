package com.offershield.college;

import com.offershield.auth.CollegeTpoEmails;
import com.offershield.auth.TpoAssignmentResult;
import com.offershield.auth.TpoAssignmentService;
import com.offershield.auth.TpoAssignmentStatus;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollegeServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock
    private CollegeRepository repository;
    @Mock
    private TpoAssignmentService tpoAssignmentService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CollegeService service;

    @Test
    void findSummary_knownCollege_returnsIdAndName() {
        College college = College.create("Example Institute of Technology", "Pune");
        when(repository.findById(college.getId())).thenReturn(Optional.of(college));

        Optional<CollegeSummary> summary = service.findSummary(college.getId());

        assertThat(summary).contains(new CollegeSummary(college.getId(), "Example Institute of Technology"));
    }

    @Test
    void findSummary_unknownCollege_empty() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.findSummary(unknown)).isEmpty();
    }

    @Test
    void findActiveSummary_activeCollege_returnsSummary() {
        College college = College.create("Active College", "Nagpur");
        when(repository.findById(college.getId())).thenReturn(Optional.of(college));

        assertThat(service.findActiveSummary(college.getId())).contains(new CollegeSummary(college.getId(), "Active College"));
    }

    @Test
    void findActiveSummary_unknownCollege_empty() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.findActiveSummary(unknown)).isEmpty();
    }

    @Test
    void create_newName_savedAndAudited() {
        when(repository.existsByNameIgnoreCase("New College")).thenReturn(false);
        when(repository.saveAndFlush(any(College.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CollegeResponse response = service.create(new CreateCollegeRequest("New College", "Pune"), ADMIN_ID);

        assertThat(response.name()).isEqualTo("New College");
        assertThat(response.city()).isEqualTo("Pune");
        assertThat(response.status()).isEqualTo(CollegeStatus.ACTIVE);
        verify(auditLogService).record(ADMIN_ID, CollegeService.COLLEGE_CREATED, response.id());
    }

    @Test
    void create_existingNameAnyCase_conflictNothingSaved() {
        when(repository.existsByNameIgnoreCase("Taken College")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateCollegeRequest("Taken College", null), ADMIN_ID))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).saveAndFlush(any(College.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void create_concurrentDuplicateHitsUniqueKey_conflict() {
        when(repository.existsByNameIgnoreCase("Racing College")).thenReturn(false);
        when(repository.saveAndFlush(any(College.class))).thenThrow(new DataIntegrityViolationException("uk_college_name"));

        assertThatThrownBy(() -> service.create(new CreateCollegeRequest("Racing College", null), ADMIN_ID))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void assignTpo_unknownCollege_notFoundNothingAssigned() {
        UUID unknown = UUID.randomUUID();
        when(repository.existsById(unknown)).thenReturn(false);

        assertThatThrownBy(() -> service.assignTpo(unknown, new AssignTpoRequest("tpo@example.com"), ADMIN_ID))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(tpoAssignmentService);
    }

    @Test
    void assignTpo_knownCollege_delegatesToAuth() {
        UUID collegeId = UUID.randomUUID();
        when(repository.existsById(collegeId)).thenReturn(true);
        when(tpoAssignmentService.assign(collegeId, "tpo@example.com", ADMIN_ID))
                .thenReturn(new TpoAssignmentResult("tpo@example.com", TpoAssignmentStatus.PENDING));

        AssignTpoResponse response = service.assignTpo(collegeId, new AssignTpoRequest("tpo@example.com"), ADMIN_ID);

        assertThat(response).isEqualTo(new AssignTpoResponse("tpo@example.com", TpoAssignmentStatus.PENDING));
    }

    @Test
    void listForAdmin_mergesTpoEmailsPerCollege() {
        College staffed = College.create("A College", "Pune");
        College empty = College.create("B College", null);
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(staffed, empty));
        when(tpoAssignmentService.findTpoEmailsByCollege()).thenReturn(Map.of(staffed.getId(),
                new CollegeTpoEmails(List.of("tpo@example.com"), List.of("pending@example.com"))));

        List<AdminCollegeResponse> rows = service.listForAdmin();

        assertThat(rows).containsExactly(
                new AdminCollegeResponse(staffed.getId(), "A College", "Pune", CollegeStatus.ACTIVE,
                        List.of("tpo@example.com"), List.of("pending@example.com")),
                new AdminCollegeResponse(empty.getId(), "B College", null, CollegeStatus.ACTIVE, List.of(), List.of()));
    }
}
