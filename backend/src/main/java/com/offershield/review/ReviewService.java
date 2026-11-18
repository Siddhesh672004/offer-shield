package com.offershield.review;

import com.offershield.analysis.ReportService;
import com.offershield.auth.UserContact;
import com.offershield.auth.UserDirectory;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.logging.LogContext;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.ForbiddenException;
import com.offershield.common.web.NotFoundException;
import com.offershield.membership.MembershipService;
import com.offershield.registry.EvidenceService;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.registry.ReportSnapshotView;
import com.offershield.submission.PdfLink;
import com.offershield.submission.PdfLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * "Ask my TPO" and the TPO's review (PRD §4 F5, §6.5; D-04, D-05, D-08, D-33.7, R-E). Identity, the student's college
 * and the TPO's college always come from the session user ID and the database, never from the request. A student asks
 * only about their own analysed submission made while an approved member of the same college (anything else is 404,
 * PRD §14.3); one case per submission (409). A TPO sees and decides only their own college's cases (another college's
 * is 404), once, at the version they looked at (409 if stale, so a second TPO deciding at the same time gets 409).
 * CONFIRMED_SCAM confirms the letter's markers straight away (the TPO is a trusted reviewer); free-mail and official
 * company domains never become markers (D-08). Each decision is audited in the same transaction and the student is
 * emailed after commit. P13: the owner of a finished report whose registry copy is not stored yet gets 409
 * {@code REPORT_NOT_READY} ("try again in a minute") instead of a 404; a CONFIRMED_SCAM response says how many markers
 * it confirmed ({@code markersRecorded}), and the email claims flagging only when there were some.
 */
@Service
@Transactional(readOnly = true)
public class ReviewService {

    static final int MAX_LISTED = 100;
    static final String ACTION_PREFIX = "REVIEW_";
    /** P13 (silent failure 18): every reason there is no PDF, including the 30-day retention (D-06). */
    static final String PDF_NOT_AVAILABLE = "No PDF is stored for this letter. It was pasted as text, file storage "
            + "is off, or it was deleted after 30 days.";
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewCaseRepository cases;
    private final ReportSnapshotService snapshots;
    private final ReportService reports;
    private final MembershipService memberships;
    private final IndicatorService indicators;
    private final EvidenceService evidence;
    private final PdfLinkService pdfLinks;
    private final UserDirectory userDirectory;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher events;

    @SuppressWarnings("java:S107")
    public ReviewService(ReviewCaseRepository cases, ReportSnapshotService snapshots, ReportService reports,
                         MembershipService memberships,
                         IndicatorService indicators, EvidenceService evidence, PdfLinkService pdfLinks,
                         UserDirectory userDirectory, AuditLogService auditLogService,
                         ApplicationEventPublisher events) {
        this.cases = cases;
        this.snapshots = snapshots;
        this.reports = reports;
        this.memberships = memberships;
        this.indicators = indicators;
        this.evidence = evidence;
        this.pdfLinks = pdfLinks;
        this.userDirectory = userDirectory;
        this.auditLogService = auditLogService;
        this.events = events;
    }

    /** PRD §6.5: only for the owner, approved now in the college the submission was made in. */
    @Transactional
    public ReviewCaseResponse request(UUID studentUserId, UUID submissionId) {
        ReportSnapshotView snapshot = snapshots.findForOwner(submissionId, studentUserId)
                .orElseThrow(() -> reports.isFinishedForOwner(studentUserId, submissionId)
                        ? ReportSnapshotService.notReady() : reportNotFound());
        UUID collegeId = memberships.findApprovedCollegeId(studentUserId)
                .filter(approved -> approved.equals(snapshot.collegeId()))
                .orElseThrow(ReviewService::reportNotFound);
        if (cases.existsBySubmissionId(submissionId)) {
            throw alreadyAsked();
        }
        ReviewCase reviewCase = ReviewCase.open(submissionId, collegeId, studentUserId);
        try {
            cases.saveAndFlush(reviewCase);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request for the same submission committed first: UNIQUE (submission_id).
            throw alreadyAsked();
        }
        log.info("review case opened");
        return ReviewCaseResponse.from(reviewCase);
    }

    /** The student's own cases, newest first. */
    public List<ReviewCaseResponse> mine(UUID studentUserId) {
        return cases.findByStudentUserIdOrderByCreatedAtDesc(studentUserId, Limit.of(MAX_LISTED)).stream()
                .map(ReviewCaseResponse::from).toList();
    }

    /** The TPO's own college's OPEN cases, oldest first, with the evidence (D-04). */
    public List<ReviewCaseDetail> queue(UUID tpoUserId) {
        UUID collegeId = requireTpoCollege(tpoUserId);
        List<ReviewCase> open = cases.findByCollegeIdAndStatusOrderByCreatedAtAscIdAsc(collegeId, ReviewStatus.OPEN,
                Limit.of(MAX_LISTED));
        Map<UUID, UserContact> students = userDirectory.findContacts(
                open.stream().map(ReviewCase::getStudentUserId).toList());
        return open.stream().map(reviewCase -> detail(reviewCase, students.get(reviewCase.getStudentUserId())))
                .toList();
    }

    /**
     * 30-day retention (D-06): the submissions among {@code submissionIds} whose review case still needs the report
     * snapshot as evidence, because it is OPEN or was decided at or after {@code decidedSince}.
     */
    public Set<UUID> stillNeedEvidence(Collection<UUID> submissionIds, Instant decidedSince) {
        return submissionIds.isEmpty() ? Set.of()
                : new HashSet<>(cases.findSubmissionsInUse(submissionIds, decidedSince));
    }

    public ReviewCaseDetail get(UUID tpoUserId, UUID caseId) {
        ReviewCase reviewCase = cases.findByIdAndCollegeId(caseId, requireTpoCollege(tpoUserId))
                .orElseThrow(ReviewService::caseNotFound);
        MDC.put(LogContext.SUBMISSION_ID, reviewCase.getSubmissionId().toString());
        return detail(reviewCase, student(reviewCase));
    }

    @Transactional
    public ReviewCaseDetail decide(UUID tpoUserId, UUID caseId, long expectedVersion, ReviewDecisionRequest request) {
        UUID collegeId = requireTpoCollege(tpoUserId);
        ReviewCase reviewCase = cases.lockByIdAndCollegeId(caseId, collegeId)
                .orElseThrow(ReviewService::caseNotFound);
        UUID submissionId = reviewCase.getSubmissionId();
        MDC.put(LogContext.SUBMISSION_ID, submissionId.toString());
        if (!Objects.equals(reviewCase.getVersion(), expectedVersion)) {
            throw new ConflictException("This case was changed by someone else. Reload and try again.");
        }
        if (!reviewCase.isOpen()) {
            throw new ConflictException("ALREADY_DECIDED", "This case has already been decided.");
        }
        int markersRecorded = 0;
        if (request.decision() == ReviewDecision.CONFIRMED_SCAM) {
            if (snapshots.find(submissionId).isEmpty()) {
                throw new ConflictException("EVIDENCE_DELETED",
                        "This letter's report was deleted after 30 days, so it can no longer be confirmed as a scam.");
            }
            markersRecorded = indicators.confirm(submissionId, tpoUserId).size();
            if (markersRecorded == 0) {
                log.info("no scam markers recorded: the letter has no shareable details");
            }
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        reviewCase.decide(request.decision(), note, tpoUserId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        cases.saveAndFlush(reviewCase);
        auditLogService.record(tpoUserId, ACTION_PREFIX + reviewCase.getStatus().name(), reviewCase.getId());

        UserContact student = student(reviewCase);
        events.publishEvent(ReviewEmails.decided(student.email(), reviewCase.getStatus(), note,
                markersRecorded));
        log.info("review case decided status={} markersRecorded={}", reviewCase.getStatus(), markersRecorded);
        return detail(reviewCase, student, markersRecorded);
    }

    /** D-05: the TPO's short-lived link to the case's PDF; 404 for another college or when there is no PDF. */
    public PdfLink pdfLink(UUID tpoUserId, UUID caseId) {
        ReviewCase reviewCase = cases.findByIdAndCollegeId(caseId, requireTpoCollege(tpoUserId))
                .orElseThrow(ReviewService::caseNotFound);
        MDC.put(LogContext.SUBMISSION_ID, reviewCase.getSubmissionId().toString());
        return pdfLinks.linkFor(reviewCase.getSubmissionId())
                .orElseThrow(() -> new NotFoundException("PDF_NOT_AVAILABLE",
                        PDF_NOT_AVAILABLE));
    }

    private ReviewCaseDetail detail(ReviewCase reviewCase, UserContact student) {
        return detail(reviewCase, student, 0);
    }

    private ReviewCaseDetail detail(ReviewCase reviewCase, UserContact student, int markersRecorded) {
        return new ReviewCaseDetail(reviewCase.getId(), reviewCase.getSubmissionId(), reviewCase.getStatus(),
                reviewCase.getNote(), student == null ? null : student.name(),
                student == null ? null : student.email(), reviewCase.getCreatedAt(), reviewCase.getDecidedAt(),
                reviewCase.getVersion(), pdfLinks.isAvailable(reviewCase.getSubmissionId()),
                evidence.find(reviewCase.getSubmissionId()).orElse(null), markersRecorded);
    }

    /** The college comes from the database, so a stale session role can't act for a college (403). */
    private UUID requireTpoCollege(UUID tpoUserId) {
        return userDirectory.findTpoCollegeId(tpoUserId)
                .orElseThrow(() -> new ForbiddenException("FORBIDDEN", "You do not have permission to do this."));
    }

    private UserContact student(ReviewCase reviewCase) {
        return userDirectory.findContact(reviewCase.getStudentUserId())
                .orElseThrow(() -> new IllegalStateException("Review case without a user row"));
    }

    private static NotFoundException reportNotFound() {
        return new NotFoundException("Report not found");
    }

    private static NotFoundException caseNotFound() {
        return new NotFoundException("Review case not found");
    }

    private static ConflictException alreadyAsked() {
        return new ConflictException("REVIEW_EXISTS", "You have already asked your TPO to review this letter.");
    }
}
