package com.offershield.college;

import com.offershield.auth.CollegeTpoEmails;
import com.offershield.auth.TpoAssignmentService;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The college feature's public face: other features call this service, never {@link CollegeRepository}. */
@Service
public class CollegeService {

    static final String COLLEGE_CREATED = "COLLEGE_CREATED";
    private static final String DUPLICATE_NAME = "A college with this name already exists.";

    private final CollegeRepository repository;
    private final TpoAssignmentService tpoAssignmentService;
    private final AuditLogService auditLogService;

    public CollegeService(CollegeRepository repository, TpoAssignmentService tpoAssignmentService,
                          AuditLogService auditLogService) {
        this.repository = repository;
        this.tpoAssignmentService = tpoAssignmentService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public Optional<CollegeSummary> findSummary(UUID collegeId) {
        return repository.findById(collegeId).map(college -> new CollegeSummary(college.getId(), college.getName()));
    }

    /** Only ACTIVE colleges accept join requests. */
    @Transactional(readOnly = true)
    public Optional<CollegeSummary> findActiveSummary(UUID collegeId) {
        return repository.findById(collegeId)
                .filter(college -> college.getStatus() == CollegeStatus.ACTIVE)
                .map(college -> new CollegeSummary(college.getId(), college.getName()));
    }

    /** ACTIVE colleges whose name contains {@code query} (case-insensitive), ordered by name, at most 20. */
    @Transactional(readOnly = true)
    public List<CollegeSearchItem> search(String query) {
        String text = query == null ? "" : query.trim();
        return repository.findTop20ByStatusAndNameContainingIgnoreCaseOrderByNameAsc(CollegeStatus.ACTIVE, text)
                .stream()
                .map(CollegeSearchItem::from)
                .toList();
    }

    /**
     * Names are unique ignoring case (and accents: the column collation is accent-insensitive). The pre-check
     * gives a clean 409; the unique key catches two admins creating the same name at once.
     */
    @Transactional
    public CollegeResponse create(CreateCollegeRequest request, UUID adminId) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new ConflictException(DUPLICATE_NAME);
        }
        College college;
        try {
            college = repository.saveAndFlush(College.create(request.name(), request.city()));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(DUPLICATE_NAME);
        }
        auditLogService.record(adminId, COLLEGE_CREATED, college.getId());
        return CollegeResponse.from(college);
    }

    /** Every college (any status) ordered by name, with its TPO and pending-TPO emails. */
    @Transactional(readOnly = true)
    public List<AdminCollegeResponse> listForAdmin() {
        Map<UUID, CollegeTpoEmails> roster = tpoAssignmentService.findTpoEmailsByCollege();
        return repository.findAllByOrderByNameAsc().stream()
                .map(college -> AdminCollegeResponse.from(college,
                        roster.getOrDefault(college.getId(), CollegeTpoEmails.NONE)))
                .toList();
    }

    /** Unknown college gives 404; the promotion rules live in the auth feature. */
    @Transactional
    public AssignTpoResponse assignTpo(UUID collegeId, AssignTpoRequest request, UUID adminId) {
        if (!repository.existsById(collegeId)) {
            throw new NotFoundException("College not found.");
        }
        return AssignTpoResponse.from(tpoAssignmentService.assign(collegeId, request.email(), adminId));
    }
}
