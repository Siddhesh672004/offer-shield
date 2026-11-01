package com.offershield.analysis;

import com.offershield.ai.Extraction;
import com.offershield.ai.ExtractionService;
import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckOutcome;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.checks.EmailDomains;
import com.offershield.analysis.checks.Letter;
import com.offershield.analysis.checks.RiskCheck;
import com.offershield.analysis.lookup.DomainValidator;
import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.analysis.scoring.Score;
import com.offershield.analysis.scoring.ScoringEngine;
import com.offershield.common.events.SubmissionCreatedEvent;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.submission.SubmissionContent;
import com.offershield.submission.SubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Analyses one submission (PRD §6.2, §7): load the decrypted letter by ID, run every {@link RiskCheck} (the online
 * C5/C6 only for a domain that passed the SSRF guard), score, save the report, then publish {@code analysis.completed}.
 *
 * <p>D-28 at-least-once handling: a duplicate {@code submission.created} (redelivery, or the sweeper re-publishing a
 * submission whose completion never left) does not analyse again; if the report exists it re-sends
 * {@code analysis.completed} from the stored report, so the submission cannot stay QUEUED. The slow steps (lookups)
 * run outside any transaction; saving is one short transaction with the {@code processed_event} row. A completion the
 * broker did not confirm throws {@link CompletionNotSentException}, so the record is retried and takes the re-send path.
 *
 * <p>P7: before the checks, the AI extraction (Groq, masked, cached; outside any transaction like the lookups) adds
 * candidates that the checks evaluate with their own rules ({@link ExtractionMerger}, R-A): the AI can only add risk
 * and never sets the score. No AI result (off, every model failed, invalid answer) → {@code aiUsed = false} and the
 * report is PARTIAL ("AI unavailable", PRD §2.1 pillar 4).
 *
 * <p>P9: before the checks, the letter's registry keys (sender domain, HMAC keys of its phone numbers and UPI IDs,
 * SimHash) are matched in-process against the shared scam registry ({@link RegistryLookup}, R-F; outside any
 * transaction, like the lookups) for C10–C12; the keys are saved with the report and sent in {@code analysis.completed}
 * for the registry snapshot. Never logs letter text, contacts, keys or domains (D-27).
 *
 * <p>P13: the report also keeps where the sender address came from (typed or taken from the letter, S2) and how many
 * distinct email domains the letter contains; a check that runs on the letter alone and throws is a bug (ERROR log,
 * "an error on our side"), while a lookup-backed one may fail for a moment (WARN, "this time").
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    /**
     * P13 (silent failure 5): checks that run on the letter alone (C1, C2, C8, C9) cannot fail for a temporary reason,
     * so an exception there is a bug: logged at ERROR with the check code, and the report says so plainly. Checks that
     * read a lookup, the known-company list or the registry may fail for a moment (WARN, "this time").
     */
    private static final Set<CheckCode> OFFLINE = EnumSet.of(CheckCode.C1, CheckCode.C2, CheckCode.C8, CheckCode.C9);

    private final SubmissionService submissions;
    private final DomainValidator domainValidator;
    private final List<RiskCheck> checks;
    private final ScoringEngine scoring;
    private final ReportService reports;
    private final ProcessedEvents processedEvents;
    private final AnalysisCompletedPublisher publisher;
    private final ExtractionService extraction;
    private final ExtractionMerger merger;
    private final RegistryLookup registry;

    @SuppressWarnings("java:S107")
    public AnalysisService(SubmissionService submissions, DomainValidator domainValidator, List<RiskCheck> checks,
                           ScoringEngine scoring, ReportService reports, ProcessedEvents processedEvents,
                           AnalysisCompletedPublisher publisher, ExtractionService extraction,
                           ExtractionMerger merger, RegistryLookup registry) {
        this.submissions = submissions;
        this.domainValidator = domainValidator;
        this.checks = checks.stream().sorted(Comparator.comparing(RiskCheck::code)).toList();
        this.scoring = scoring;
        this.reports = reports;
        this.processedEvents = processedEvents;
        this.publisher = publisher;
        this.extraction = extraction;
        this.merger = merger;
        this.registry = registry;
    }

    public void analyse(SubmissionCreatedEvent event) {
        UUID submissionId = event.payload().submissionId();
        UUID eventId = event.eventId();
        if (processedEvents.isProcessed(ReportService.CONSUMER, eventId)) {
            Optional<Completion> stored = reports.findCompletion(submissionId);
            if (stored.isEmpty()) {
                log.info("analysis skipped: duplicate event");
                return;
            }
            log.info("analysis skipped: duplicate event, re-sending the stored completion");
            send(stored.get());
            return;
        }
        Optional<SubmissionContent> content = submissions.findContent(submissionId);
        if (content.isEmpty()) {
            reports.markSkipped(eventId);
            log.info("analysis skipped: the submission or its letter text no longer exists");
            return;
        }
        SubmissionContent submission = content.get();
        Optional<Extraction> ai = extract(submission.text());
        Letter letter = letterOf(submission).withAi(merger.findings(ai));
        RegistryLookup.Result registered = registry.lookup(letter);
        letter = letter.withRegistry(registered.findings());
        List<CheckResult> results = runChecks(letter);
        Score score = scoring.score(results);
        boolean aiUsed = ai.isPresent();
        boolean partial = !aiUsed || results.stream().anyMatch(result -> result.outcome() == CheckOutcome.UNAVAILABLE);
        String senderDomain = letter.domain() == null ? null : letter.domain().host();
        boolean saved = reports.save(eventId, new NewReport(submissionId, submission.ownerUserId(),
                submission.collegeId(), senderDomain, score, partial, results, aiUsed, merger.details(ai),
                registered.markers(), registered.findings().match().unverifiedReporters(),
                senderDomain == null ? null : letter.senderSource(), letterEmailDomains(letter.text())));
        log.info("analysis {} band={} score={} hardOverride={} partial={} aiUsed={}", saved ? "saved" : "already saved",
                score.band(), score.points(), score.hardOverride(), partial, aiUsed);
        send(reports.findCompletion(submissionId)
                .orElseThrow(() -> new IllegalStateException("report missing after save")));
    }

    /** Every check, in check order. A check that fails unexpectedly is UNAVAILABLE (the report still arrives). */
    List<CheckResult> runChecks(Letter letter) {
        return checks.stream().map(check -> evaluate(check, letter)).toList();
    }

    private CheckResult evaluate(RiskCheck check, Letter letter) {
        try {
            CheckResult result = check.evaluate(letter);
            if (result == null || result.code() != check.code()) {
                throw new IllegalStateException("check returned a result for another code");
            }
            return result;
        } catch (RuntimeException ex) {
            if (OFFLINE.contains(check.code())) {
                log.error("check failed check={} error={}", check.code(), ex.getClass().getSimpleName());
                return CheckResult.unavailable(check.code(), "This check could not run because of an error on our "
                        + "side. The rest of the report still applies.", check.code().sourceKind());
            }
            log.warn("check failed check={} error={}", check.code(), ex.getClass().getSimpleName());
            return CheckResult.unavailable(check.code(), "This check could not run this time.", check.code().sourceKind());
        }
    }

    /** The AI pass (PRD §8): never blocks the report; any failure means "AI unavailable" and a PARTIAL report. */
    private Optional<Extraction> extract(String text) {
        try {
            Optional<Extraction> result = extraction.extract(text);
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException ex) {
            log.warn("ai extraction failed error={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }


    /**
     * P13 (S2): how many distinct registrable email domains the letter itself contains (a host the SSRF guard refuses
     * counts as itself). A sender domain taken from the letter becomes a scam marker only when it is the only one.
     */
    private int letterEmailDomains(String text) {
        return (int) EmailDomains.in(text).stream()
                .map(host -> domainValidator.validate(host).map(ValidatedDomain::registrableDomain).orElse(host))
                .distinct().count();
    }
    private Letter letterOf(SubmissionContent content) {
        ValidatedDomain domain = null;
        if (content.senderDomain() != null) {
            domain = domainValidator.validate(content.senderDomain()).orElse(null);
            if (domain == null) {
                log.info("sender domain refused by the SSRF guard: online checks skipped");
            }
        }
        return new Letter(content.text(), content.senderEmail(), content.senderDomain(), domain,
                content.recruiterPhone(), content.upiId(), null, null, content.senderSource());
    }

    private void send(Completion completion) {
        if (!publisher.publish(completion)) {
            throw new CompletionNotSentException();
        }
    }
}
