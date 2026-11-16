package com.offershield.scamreport;

import com.offershield.analysis.ReportService;
import com.offershield.auth.UserDirectory;
import com.offershield.common.Ids;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.concurrency.UserLocks;
import com.offershield.common.crypto.SealedText;
import com.offershield.common.crypto.TextCipher;
import com.offershield.common.logging.LogContext;
import com.offershield.common.web.BadRequestException;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.NotFoundException;
import com.offershield.common.web.TooManyRequestsException;
import com.offershield.registry.EvidenceService;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.ReportSnapshotService;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Report a scam and the admin's moderation (PRD §4 F6, §6.4; D-12, D-33.4, D-33.7, R-E). Identity always comes from
 * the session user ID. A user reports only their own analysed submission (anything else is 404, PRD §14.3), once per
 * submission (409), at most 5 times in 24 hours (429, counted in the database under a per-user lock, so parallel
 * reports cannot pass it; P13). The report's markers become UNVERIFIED
 * (a CONFIRMED one stays CONFIRMED) in the same transaction. The admin confirms (markers CONFIRMED) or rejects (that
 * reporter is withdrawn from them, D-33.4) at the version they looked at; the decision is audited in the same
 * transaction and the reporter is emailed after commit. P13: the owner of a finished report whose registry copy is not
 * stored yet gets 409 {@code REPORT_NOT_READY} instead of a 404; responses say how many markers a report or a
 * confirmation recorded ({@code markersRecorded}), emails claim flagging only when there were some, and a decision
 * email that cannot be sent (no contact) is logged.
 */
@Service
@Transactional(readOnly = true)
public class ScamReportService {

    static final int MAX_REPORTS_PER_WINDOW = 5;
    static final Duration REPORT_WINDOW = Duration.ofDays(1);
    static final int MAX_LISTED = 100;
    static final String CONFIRMED = "SCAM_REPORT_CONFIRMED";
    static final String REJECTED = "SCAM_REPORT_REJECTED";
    /** P13 (silent failure 18): every reason there is no PDF, including the 30-day retention (D-06). */
    static final String PDF_NOT_AVAILABLE = "No PDF is stored for this letter. It was pasted as text, file storage "
            + "is off, or it was deleted after 30 days.";
    private static final Logger log = LoggerFactory.getLogger(ScamReportService.class);

    private final ScamReportRepository reports;
    private final ReportSnapshotService snapshots;
    private final ReportService analysedReports;
    private final IndicatorService indicators;
    private final EvidenceService evidence;
    private final PdfLinkService pdfLinks;
    private final TextCipher textCipher;
    private final UserDirectory userDirectory;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher events;
    private final UserLocks reportLocks = new UserLocks();

    @SuppressWarnings("java:S107")
    public ScamReportService(ScamReportRepository reports, ReportSnapshotService snapshots,
                             ReportService analysedReports, IndicatorService indicators,
                             EvidenceService evidence, PdfLinkService pdfLinks, TextCipher textCipher,
                             UserDirectory userDirectory, AuditLogService auditLogService,
                             ApplicationEventPublisher events) {
        this.reports = reports;
        this.snapshots = snapshots;
        this.analysedReports = analysedReports;
        this.indicators = indicators;
        this.evidence = evidence;
        this.pdfLinks = pdfLinks;
        this.textCipher = textCipher;
        this.userDirectory = userDirectory;
        this.auditLogService = auditLogService;
        this.events = events;
    }

    /** PRD §6.4 steps 1–3. */
    @Transactional
    public ScamReportResponse report(UUID reporterUserId, CreateScamReportRequest request) {
        // P13 (S3): the daily count and the insert run under the reporter's lock until this transaction ends, so
        // reports sent at once cannot pass the limit together. Taken before the first read of the transaction.
        reportLocks.lockUntilTransactionEnds(reporterUserId);
        UUID submissionId = request.submissionId();
        snapshots.findForOwner(submissionId, reporterUserId)
                .orElseThrow(() -> analysedReports.isFinishedForOwner(reporterUserId, submissionId)
                        ? ReportSnapshotService.notReady() : new NotFoundException("Report not found"));
        if (reports.existsBySubmissionIdAndReporterUserId(submissionId, reporterUserId)) {
            throw alreadyReported();
        }
        enforceDailyLimit(reporterUserId);

        String description = request.description().replace("\u0000", "").strip();
        if (description.isEmpty()) {
            throw new BadRequestException("DESCRIPTION_REQUIRED", "Describe what happened.");
        }
        UUID id = Ids.newId();
        ScamReport report = ScamReport.open(id, submissionId, reporterUserId,
                textCipher.encrypt(description, descriptionAad(id)), request.amountAsked());
        try {
            reports.saveAndFlush(report);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent report of the same submission committed first: UNIQUE (submission, reporter).
            throw alreadyReported();
        }
        int markersRecorded = indicators.reportScam(submissionId, reporterUserId).size();
        if (markersRecorded == 0) {
            log.info("no scam markers recorded: the letter has no shareable details");
        }
        log.info("scam report opened markersRecorded={}", markersRecorded);
        return ScamReportResponse.from(report, markersRecorded);
    }

    /** The user's own reports, newest first. */
    public List<ScamReportResponse> mine(UUID reporterUserId) {
        return reports.findByReporterUserIdOrderByCreatedAtDesc(reporterUserId, Limit.of(MAX_LISTED)).stream()
                .map(ScamReportResponse::from).toList();
    }

    /** The admin queue: OPEN reports oldest first, decided ones most recently decided first. */
    public List<AdminScamReportResponse> list(ScamReportStatus status) {
        List<ScamReport> found = status == ScamReportStatus.OPEN
                ? reports.findByStatusOrderByCreatedAtAscIdAsc(status, Limit.of(MAX_LISTED))
                : reports.findByStatusOrderByDecidedAtDescIdDesc(status, Limit.of(MAX_LISTED));
        return found.stream().map(this::adminView).toList();
    }

    /** PRD §6.4 steps 6–7 at the version the admin looked at (409 if stale or already decided). */
    @Transactional
    public AdminScamReportResponse decide(UUID adminUserId, UUID reportId, long expectedVersion,
                                          ScamReportDecision decision) {
        ScamReport report = reports.lockById(reportId)
                .orElseThrow(() -> new NotFoundException("Scam report not found"));
        UUID submissionId = report.getSubmissionId();
        MDC.put(LogContext.SUBMISSION_ID, submissionId.toString());
        if (!Objects.equals(report.getVersion(), expectedVersion)) {
            throw new ConflictException("This scam report was changed by someone else. Reload and try again.");
        }
        if (!report.isOpen()) {
            throw new ConflictException("ALREADY_DECIDED", "This scam report has already been decided.");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int markersRecorded = 0;
        if (decision == ScamReportDecision.CONFIRM) {
            if (snapshots.find(submissionId).isEmpty()) {
                throw new ConflictException("EVIDENCE_DELETED",
                        "This letter's report was deleted after 30 days, so it can no longer be confirmed.");
            }
            markersRecorded = indicators.confirm(submissionId, adminUserId).size();
            if (markersRecorded == 0) {
                log.info("no scam markers recorded: the letter has no shareable details");
            }
            report.confirm(adminUserId, now);
        } else {
            indicators.withdrawReport(submissionId, report.getReporterUserId());
            report.reject(adminUserId, now);
        }
        reports.saveAndFlush(report);
        boolean confirmed = report.getStatus() == ScamReportStatus.CONFIRMED;
        auditLogService.record(adminUserId, confirmed ? CONFIRMED : REJECTED, report.getId());
        int recorded = markersRecorded;
        userDirectory.findContact(report.getReporterUserId()).ifPresentOrElse(
                reporter -> events.publishEvent(ScamReportEmails.decided(reporter.email(), report.getStatus(), recorded)),
                // P13 (silent failure 18): never skip the reporter's email without a trace (IDs only, D-27).
                () -> log.warn("scam report decision email skipped: no contact for the reporter"));
        log.info("scam report decided status={} markersRecorded={}", report.getStatus(), markersRecorded);
        return adminView(report, markersRecorded);
    }

    /** D-05: the admin's short-lived link to the reported letter's PDF; 404 when there is none. */
    public PdfLink pdfLink(UUID reportId) {
        ScamReport report = reports.findById(reportId)
                .orElseThrow(() -> new NotFoundException("Scam report not found"));
        MDC.put(LogContext.SUBMISSION_ID, report.getSubmissionId().toString());
        return pdfLinks.linkFor(report.getSubmissionId())
                .orElseThrow(() -> new NotFoundException("PDF_NOT_AVAILABLE",
                        PDF_NOT_AVAILABLE));
    }

    /** D-12: at most 5 scam reports per user in any 24 hours, counted in the database. */
    private void enforceDailyLimit(UUID reporterUserId) {
        Instant now = Instant.now();
        Instant since = now.minus(REPORT_WINDOW);
        if (reports.countByReporterUserIdAndCreatedAtAfter(reporterUserId, since) < MAX_REPORTS_PER_WINDOW) {
            return;
        }
        Instant oldest = reports.findOldestCreatedAtAfter(reporterUserId, since).orElse(now);
        Duration retryAfter = Duration.between(now, oldest.plus(REPORT_WINDOW));
        throw new TooManyRequestsException("SCAM_REPORT_LIMIT",
                "You can report up to 5 scams a day. Please try again later.",
                retryAfter.isNegative() ? Duration.ZERO : retryAfter);
    }

    /**
     * 30-day retention (D-06): the submissions among {@code submissionIds} whose scam report still needs the report
     * snapshot as evidence, because it is OPEN or was decided at or after {@code decidedSince}.
     */
    public Set<UUID> stillNeedEvidence(Collection<UUID> submissionIds, Instant decidedSince) {
        return submissionIds.isEmpty() ? Set.of()
                : new HashSet<>(reports.findSubmissionsInUse(submissionIds, decidedSince));
    }

    /**
     * 30-day retention (D-06): wipes the encrypted descriptions of up to {@code limit} reports decided before
     * {@code decidedBefore} (an OPEN report keeps its description: the admin still needs it). The report row, its
     * decision and amount stay. A report is always made after its letter, so that letter's 30 days are over too.
     *
     * @return how many descriptions were wiped
     */
    @Transactional
    public int wipeDescriptionsDecidedBefore(Instant decidedBefore, int limit) {
        List<UUID> due = reports.findDescriptionsDecidedBefore(decidedBefore, Limit.of(limit));
        return due.isEmpty() ? 0 : reports.wipeDescriptions(due);
    }

    private AdminScamReportResponse adminView(ScamReport report) {
        return adminView(report, 0);
    }

    private AdminScamReportResponse adminView(ScamReport report, int markersRecorded) {
        SealedText sealed = report.getDescription();
        String description = sealed == null ? null : textCipher.decrypt(sealed, descriptionAad(report.getId()));
        return new AdminScamReportResponse(report.getId(), report.getSubmissionId(), report.getStatus(), description,
                report.getAmountAsked(), report.getCreatedAt(), report.getDecidedAt(), report.getVersion(),
                pdfLinks.isAvailable(report.getSubmissionId()), evidence.find(report.getSubmissionId()).orElse(null),
                markersRecorded);
    }

    private static ConflictException alreadyReported() {
        return new ConflictException("SCAM_REPORT_EXISTS", "You have already reported this letter as a scam.");
    }

    private static byte[] descriptionAad(UUID reportId) {
        return (reportId + ":description").getBytes(StandardCharsets.UTF_8);
    }
}
