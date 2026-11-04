package com.offershield.analysis;

import com.offershield.ai.Extraction;
import com.offershield.ai.ExtractionService;
import com.offershield.analysis.checks.FreeMailSenderCheck;
import com.offershield.analysis.checks.MailSetupCheck;
import com.offershield.analysis.checks.MoneyRequestCheck;
import com.offershield.analysis.checks.OffPlatformContactCheck;
import com.offershield.analysis.checks.PressureLanguageCheck;
import com.offershield.analysis.checks.RiskCheck;
import com.offershield.analysis.checks.YoungDomainCheck;
import com.offershield.analysis.lookup.DnsClient;
import com.offershield.analysis.lookup.RdapClient;
import com.offershield.common.FreeMailDomains;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.events.SubmissionCreatedEvent;
import com.offershield.registry.IndicatorHasher;
import com.offershield.registry.MatchService;
import com.offershield.registry.RegistryMatch;
import com.offershield.submission.SourceType;
import com.offershield.submission.SubmissionContent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Fake submissions and events for analysis unit tests. Every value is made up. */
final class AnalysisFixtures {

    static final UUID SUBMISSION = UUID.fromString("0190c9a0-0000-7000-8000-0000000000a1");
    static final UUID OWNER = UUID.fromString("0190c9a0-0000-7000-8000-0000000000b1");
    static final Instant CREATED = Instant.parse("2026-10-20T10:00:00Z");
    /** An AI extraction that ran and found nothing: the report is not PARTIAL because of the AI. */
    static final Extraction NOTHING_EXTRACTED = new Extraction(null, null, null, null, List.of(), List.of(), List.of(),
            "openai/gpt-oss-120b", false);

    private AnalysisFixtures() {
    }

    /** The six P6 checks, with the online ones on the given (mocked) clients. */
    static List<RiskCheck> checks(RdapClient rdap, DnsClient dns) {
        FreeMailDomains freeMail = new FreeMailDomains();
        return List.of(new PressureLanguageCheck(), new MailSetupCheck(dns, freeMail), new MoneyRequestCheck(),
                new OffPlatformContactCheck(freeMail), new YoungDomainCheck(rdap, freeMail),
                new FreeMailSenderCheck(freeMail));
    }

    /** A registry lookup whose registry has no markers (a real hasher with a test pepper, a mocked match). */
    static RegistryLookup registryFindingNothing() {
        MatchService matches = org.mockito.Mockito.mock(MatchService.class);
        org.mockito.Mockito.when(matches.match(org.mockito.ArgumentMatchers.any())).thenReturn(RegistryMatch.NONE);
        return new RegistryLookup(new IndicatorHasher("test-pepper-" + "x".repeat(40)), matches,
                noKnownCompanies());
    }

    /** The copied-letter guard with an empty known-company list (it never finds an official sender). */
    static com.offershield.analysis.checks.CopiedLetterGuard noKnownCompanies() {
        return new com.offershield.analysis.checks.CopiedLetterGuard(
                com.offershield.analysis.checks.KnownCompanyFixtures.knownCompanies(), new FreeMailDomains());
    }

    /** An extraction service whose AI always answers with {@link #NOTHING_EXTRACTED}. */
    static ExtractionService aiFindingNothing() {
        ExtractionService extraction = org.mockito.Mockito.mock(ExtractionService.class);
        org.mockito.Mockito.when(extraction.extract(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(NOTHING_EXTRACTED));
        return extraction;
    }

    static SubmissionCreatedEvent event() {
        return SubmissionCreatedEvent.of(new SubmissionCreatedEvent.Payload(SUBMISSION, OWNER, null, "TEXT", "a".repeat(64)),
                CREATED, "req-p6");
    }

    static SubmissionContent content(String text, String senderEmail, String senderDomain) {
        return new SubmissionContent(SUBMISSION, OWNER, null, SourceType.TEXT, text, senderEmail, senderDomain, null, null);
    }

    static Completion completion() {
        return new Completion(new AnalysisCompletedEvent.Payload(SUBMISSION, OWNER, null, "READY", 25, "HIGH", true,
                List.of(), List.of(), null, List.of(), List.of(), null, true), CREATED.plusSeconds(5));
    }
}
