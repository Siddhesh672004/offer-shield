package com.offershield.analysis;

import com.offershield.ai.ReportFacts;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.common.web.NotFoundException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns {@code report} and {@code report_signal}. Saving is one short transaction with the {@code processed_event} row
 * of the {@code analysis} consumer (D-28), so a report is never saved twice and a saved report always marks its event
 * as done. The owner read is scoped by owner (PRD §14.3: someone else's report is a 404), and so is "Explain simply"
 * (P7-03), whose AI-written text is saved once in {@code report.explanation}.
 */
@Service
public class ReportService {

    /** The consumer group of {@code submission.created}, also its {@code processed_event.consumer} value. */
    public static final String CONSUMER = "analysis";

    private final ReportRepository reports;
    private final ProcessedEvents processedEvents;

    public ReportService(ReportRepository reports, ProcessedEvents processedEvents) {
        this.reports = reports;
        this.processedEvents = processedEvents;
    }

    /** @return {@code false} when the event was already processed or the submission already has a report */
    @Transactional
    public boolean save(UUID eventId, NewReport report) {
        if (!processedEvents.markProcessed(CONSUMER, eventId) || reports.existsById(report.submissionId())) {
            return false;
        }
        reports.save(new Report(report));
        return true;
    }

    /** Marks an event done without a report (its submission or letter text no longer exists). */
    @Transactional
    public void markSkipped(UUID eventId) {
        processedEvents.markProcessed(CONSUMER, eventId);
    }

    /** The stored report as an {@code analysis.completed} payload, for the first send and for a re-send. */
    @Transactional(readOnly = true)
    public Optional<Completion> findCompletion(UUID submissionId) {
        return reports.findWithSignalsById(submissionId).map(ReportService::toCompletion);
    }

    /**
     * P13 (silent failure 1): true when {@code ownerUserId}'s submission has a saved report, i.e. its analysis finished
     * (READY or PARTIAL). Lets "Ask my TPO" and "Report scam" answer "still being prepared" to the owner while the
     * registry's copy is on its way, without telling anyone else that the ID exists.
     */
    @Transactional(readOnly = true)
    public boolean isFinishedForOwner(UUID ownerUserId, UUID submissionId) {
        return reports.existsByIdAndOwnerUserId(submissionId, ownerUserId);
    }

    /** P13 snapshot reconciliation: IDs of reports saved in [from, to), oldest first, at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<UUID> findIdsFinishedBetween(Instant from, Instant to, int limit) {
        return reports.findIdsCreatedBetween(from, to, Limit.of(limit));
    }

    @Transactional(readOnly = true)
    public ReportResponse getForOwner(UUID ownerUserId, UUID submissionId) {
        return reports.findByIdAndOwnerUserId(submissionId, ownerUserId)
                .map(ReportResponse::from)
                .orElseThrow(() -> new NotFoundException("Report not found"));
    }

    /**
     * What "Explain simply" needs, scoped by owner like {@link #getForOwner} (someone else's report → the same 404):
     * the saved explanation, if any, and the report's facts (band, score, signals; never the letter).
     */
    @Transactional(readOnly = true)
    public ExplainSource explainSourceForOwner(UUID ownerUserId, UUID submissionId) {
        Report report = reports.findByIdAndOwnerUserId(submissionId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("Report not found"));
        return new ExplainSource(report.getExplanation(), new ReportFacts(report.getBand().name(),
                report.getBand().message(), report.getScore(), report.isHardOverride(),
                report.getSignals().stream().map(signal -> new ReportFacts.Fact(signal.getCheckCode().name(),
                        signal.getCheckCode().title(), signal.getOutcome().name(), signal.getPoints(),
                        signal.getExplanation(), signal.getEvidence(), signal.getSourceKind(), signal.isAiExtracted()))
                        .toList()));
    }

    /**
     * Saves an AI-written explanation unless one was saved meanwhile (two clicks at once); returns the one that is
     * stored, so both callers show the same text.
     */
    @Transactional
    public String saveExplanation(UUID submissionId, String explanation) {
        reports.saveExplanationIfAbsent(submissionId, explanation);
        return reports.findExplanationById(submissionId).orElse(explanation);
    }

    /**
     * 30-day retention (D-06): deletes the reports of these submissions with their signals and saved explanation, in
     * the caller's transaction (the retention job wipes the submissions in the same one).
     *
     * @return how many reports were deleted (0 for submissions without one, e.g. already cleaned)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteForSubmissions(Collection<UUID> submissionIds) {
        return submissionIds.isEmpty() ? 0 : reports.deleteByIdIn(submissionIds);
    }

    /** A report's saved explanation ({@code null} when none yet) and its facts. */
    public record ExplainSource(String explanation, ReportFacts facts) {

        @Override
        public String toString() {
            return "ExplainSource[redacted]";
        }
    }

    private static Completion toCompletion(Report report) {
        AnalysisCompletedEvent.Payload payload = new AnalysisCompletedEvent.Payload(report.getId(),
                report.getOwnerUserId(), report.getCollegeId(),
                report.isPartial() ? AnalysisCompletedEvent.PARTIAL : AnalysisCompletedEvent.READY,
                report.getScore(), report.getBand().name(), report.isHardOverride(), report.unavailableChecks(),
                report.getSignals().stream().map(signal -> new AnalysisCompletedEvent.Signal(
                        signal.getCheckCode().name(), signal.getOutcome().name(), signal.getPoints(),
                        signal.isHardOverride(), signal.getExplanation(), signal.getEvidence(), signal.getSourceKind(),
                        signal.isAiExtracted())).toList(),
                report.getSenderDomain(), report.getPhoneHmacs(), report.getUpiHmacs(), report.getSimhash(),
                report.isIndicatorsComplete(),
                report.getSenderSource() == null ? null : report.getSenderSource().name(),
                report.getLetterEmailDomains(), report.getMarkerHints());
        return new Completion(payload, report.getCreatedAt());
    }
}
