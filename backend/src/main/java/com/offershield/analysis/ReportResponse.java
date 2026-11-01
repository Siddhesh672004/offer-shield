package com.offershield.analysis;

import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckOutcome;
import com.offershield.analysis.scoring.Band;
import com.offershield.common.SenderSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/reports/{submissionId}} (owner only). {@code bandMessage} is the PRD §7.2 text, verbatim;
 * {@code score} is the capped sum of points; {@code hardOverride} says High was forced by C1/C10/C11 whatever the
 * points; {@code partial} with {@code unavailableChecks} names the checks that could not run (PRD §2.1 pillar 4).
 * {@code aiUsed = false} means "AI unavailable" (the report is then also partial); {@code aiDetails} holds the facts
 * the AI read in the letter, labelled "AI-extracted", or is {@code null} when there are none (P7). {@code registry}
 * summarises C10–C12 for the trust labels (P9). {@code senderSource} (P13, S2): {@code TYPED} when the student
 * typed the sender email, {@code LETTER} when the sender address was taken from the letter itself, {@code null} without
 * a sender domain (or for a report made before P13).
 */
public record ReportResponse(UUID submissionId, int score, Band band, String bandMessage, boolean hardOverride,
                             boolean partial, boolean aiUsed, AiDetailsResponse aiDetails, String senderDomain,
                             SenderSource senderSource, List<String> unavailableChecks, RegistryResponse registry,
                             List<SignalResponse> signals, Instant createdAt) {

    /** "AI-extracted" facts (PRD §7: AI-derived fields are labelled). Each value may be {@code null}. */
    public record AiDetailsResponse(String label, String companyName, String recruiterName, String ctc,
                                    String joiningDate) {

        public static final String LABEL = "AI-extracted";

        static AiDetailsResponse from(AiDetails details) {
            return details.isEmpty() ? null : new AiDetailsResponse(LABEL, details.companyName(),
                    details.recruiterName(), details.ctc(), details.joiningDate());
        }
    }

    /**
     * The shared-registry outcome, so the two trust levels are never mixed up (CLAUDE.md product rules):
     * {@code confirmedScam} = C10 or C11 forced High from a marker a TPO or the admin confirmed ("Confirmed scam",
     * source kind 4); {@code wordingWarning} = C11 matched a confirmed letter but the sender is the named company's
     * official domain (+20, "wording resembles a reported scam"); {@code reportedBy} = N distinct users behind matching
     * unconfirmed reports (C12, "Reported as a scam by N users (not verified)", source kind 5), 0 when none.
     */
    public record RegistryResponse(boolean confirmedScam, boolean wordingWarning, int reportedBy) {

        static RegistryResponse from(Report report) {
            boolean confirmed = report.getSignals().stream().anyMatch(signal -> signal.getOutcome()
                    == CheckOutcome.TRIGGERED && signal.isHardOverride()
                    && (signal.getCheckCode() == CheckCode.C10 || signal.getCheckCode() == CheckCode.C11));
            boolean wording = report.getSignals().stream().anyMatch(signal -> signal.getCheckCode() == CheckCode.C11
                    && signal.getOutcome() == CheckOutcome.TRIGGERED && !signal.isHardOverride());
            boolean reported = report.getSignals().stream().anyMatch(signal -> signal.getCheckCode() == CheckCode.C12
                    && signal.getOutcome() == CheckOutcome.TRIGGERED);
            return new RegistryResponse(confirmed, wording, reported ? report.getReportedBy() : 0);
        }
    }

    static ReportResponse from(Report report) {
        return new ReportResponse(report.getId(), report.getScore(), report.getBand(), report.getBand().message(),
                report.isHardOverride(), report.isPartial(), report.isAiUsed(),
                AiDetailsResponse.from(report.getAiDetails()), report.getSenderDomain(), report.getSenderSource(),
                report.unavailableChecks(),
                RegistryResponse.from(report), report.getSignals().stream().map(SignalResponse::from).toList(),
                report.getCreatedAt());
    }
}
