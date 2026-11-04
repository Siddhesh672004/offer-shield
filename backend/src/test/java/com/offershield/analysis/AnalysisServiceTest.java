package com.offershield.analysis;

import com.offershield.ai.Extraction;
import com.offershield.ai.ExtractionService;
import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckOutcome;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.checks.CompanyDomainMismatchCheck;
import com.offershield.analysis.checks.ConfirmedIndicatorCheck;
import com.offershield.analysis.checks.CopiedLetterGuard;
import com.offershield.analysis.checks.KnownCompanyFixtures;
import com.offershield.analysis.checks.Letter;
import com.offershield.analysis.checks.Letters;
import com.offershield.analysis.checks.LookalikeDomainCheck;
import com.offershield.analysis.checks.NearDuplicateCheck;
import com.offershield.analysis.checks.RiskCheck;
import com.offershield.analysis.checks.UnverifiedReportsCheck;
import com.offershield.analysis.lookup.DnsClient;
import com.offershield.analysis.lookup.DomainValidator;
import com.offershield.analysis.lookup.MailRecords;
import com.offershield.analysis.lookup.RdapClient;
import com.offershield.analysis.lookup.RdapLookup;
import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.ScoringEngine;
import com.offershield.common.FreeMailDomains;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.company.KnownCompanies;
import com.offershield.company.KnownCompanyDirectory;
import com.offershield.registry.IndicatorHasher;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.MatchService;
import com.offershield.registry.MatchedMarker;
import com.offershield.registry.RegistryMatch;
import com.offershield.submission.SubmissionService;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The analysis pipeline without Kafka or a database: checks → score → the report handed to {@link ReportService}. */
class AnalysisServiceTest {

    private final SubmissionService submissions = mock(SubmissionService.class);
    private final ReportService reports = mock(ReportService.class);
    private final AnalysisCompletedPublisher publisher = mock(AnalysisCompletedPublisher.class);
    private final RdapClient rdap = mock(RdapClient.class);
    private final DnsClient dns = mock(DnsClient.class);
    private final ExtractionService extraction = mock(ExtractionService.class);

    @BeforeEach
    void aiAnswersWithNothing() {
        when(extraction.extract(any())).thenReturn(Optional.of(AnalysisFixtures.NOTHING_EXTRACTED));
    }

    @Test
    void depositLetter_high() {
        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)),
                Letters.fixture(Letters.DEPOSIT_SCAM), null, null);

        assertThat(report.score().band()).isEqualTo(Band.HIGH);
        assertThat(report.score().hardOverride()).isTrue();
        assertThat(report.partial()).isFalse();
        CheckResult moneyRequest = result(report, CheckCode.C1);
        assertThat(moneyRequest.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(moneyRequest.sourceKind()).isEqualTo(1);
        assertThat(moneyRequest.evidence()).contains("refundable security deposit");
    }

    @Test
    void genuineLetter_low() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));

        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)), Letters.fixture(Letters.GENUINE),
                "talent@northwind-analytics.com", "northwind-analytics.com");

        assertThat(report.score().band()).isEqualTo(Band.LOW);
        assertThat(report.score().points()).isZero();
        assertThat(report.results()).allSatisfy(result -> assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED));
    }

    @Test
    void genuineLetterWithFraudWarning_notHigh() {
        // P13 (01 §3): a fraud warning, an employer benefit and "borne by the company" are not money requests.
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));

        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)),
                Letters.fixture("genuine-with-fraud-warning.txt"), "talent@northwind-analytics.com",
                "northwind-analytics.com");

        assertThat(result(report, CheckCode.C1).outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(report.score().hardOverride()).isFalse();
        assertThat(report.score().band()).isNotEqualTo(Band.HIGH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "[::1]", "169.254.169.254", "evil.com:8080/x", "http://x", "2130706433",
            "0x7f.0.0.1", "localhost", "metadata.google.internal"})
    void ssrfSenders_noLookupCalls(String senderDomain) {
        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)), "Dear Candidate, welcome aboard.",
                "x@" + senderDomain, senderDomain);

        verifyNoInteractions(rdap, dns);
        assertThat(result(report, CheckCode.C5).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result(report, CheckCode.C6).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(report.senderDomain()).isNull(); // never stored as a domain (no registry marker later)
        assertThat(report.partial()).isFalse();
    }

    @Test
    void failingCheck_unavailableNotFatal_classOnlyLogged() {
        RiskCheck broken = new RiskCheck() {
            @Override
            public CheckCode code() {
                return CheckCode.C9;
            }

            @Override
            public CheckResult evaluate(Letter letter) {
                throw new IllegalStateException("SECRET-LETTER-TEXT");
            }
        };
        List<RiskCheck> checks = new ArrayList<>(AnalysisFixtures.checks(rdap, dns));
        checks.removeIf(check -> check.code() == CheckCode.C9);
        checks.add(broken);

        NewReport report;
        try (LogCapture logs = LogCapture.of(AnalysisService.class)) {
            report = analyse(service(checks), "Dear Candidate, welcome.", null, null);
            assertThat(logs.events()).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("check failed", "C9", "IllegalStateException"));
            assertThat(logs.events()).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("SECRET-LETTER-TEXT"));
        }
        assertThat(result(report, CheckCode.C9).outcome()).isEqualTo(CheckOutcome.UNAVAILABLE);
        assertThat(report.partial()).isTrue();
    }

    @Test
    void offlineCheckFails_errorLoggedWithCode_notCalledTemporary() {
        // P13 (silent failure 5): C1 runs on the letter alone, so an exception is a bug, not "this time".
        List<RiskCheck> checks = new ArrayList<>(AnalysisFixtures.checks(rdap, dns));
        checks.removeIf(check -> check.code() == CheckCode.C1);
        checks.add(broken(CheckCode.C1));

        NewReport report;
        try (LogCapture logs = LogCapture.of(AnalysisService.class)) {
            report = analyse(service(checks), "Dear Candidate, welcome.", null, null);
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("check failed", "C1", "IllegalStateException");
            });
        }
        CheckResult c1 = result(report, CheckCode.C1);
        assertThat(c1.outcome()).isEqualTo(CheckOutcome.UNAVAILABLE);
        assertThat(c1.explanation()).doesNotContain("this time").contains("error on our side");
        assertThat(report.partial()).isTrue();
    }

    @Test
    void lookupCheckFails_warnedAsTemporary() {
        List<RiskCheck> checks = new ArrayList<>(AnalysisFixtures.checks(rdap, dns));
        checks.removeIf(check -> check.code() == CheckCode.C5);
        checks.add(broken(CheckCode.C5));

        NewReport report;
        try (LogCapture logs = LogCapture.of(AnalysisService.class)) {
            report = analyse(service(checks), "Dear Candidate, welcome.", null, null);
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage()).contains("check failed", "C5");
            });
        }
        assertThat(result(report, CheckCode.C5).explanation()).contains("this time");
    }

    private static RiskCheck broken(CheckCode code) {
        return new RiskCheck() {
            @Override
            public CheckCode code() {
                return code;
            }

            @Override
            public CheckResult evaluate(Letter letter) {
                throw new IllegalStateException("SECRET-LETTER-TEXT");
            }
        };
    }

    @Test
    void explanations_neverClaimSafety() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(30))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(false, false));
        AnalysisService service = service(AnalysisFixtures.checks(rdap, dns));
        List<String> fixtures = List.of(Letters.GENUINE, Letters.DEPOSIT_SCAM, Letters.HINGLISH_FEE_SCAM,
                Letters.NO_FEE_GENUINE, Letters.NO_INTERVIEW_PAY_FEE, Letters.WHATSAPP_ONLY, Letters.PRESSURE);

        for (String fixture : fixtures) {
            for (String sender : new String[]{"hr@young-firm.com", "jobs@gmail.com", null}) {
                String domain = sender == null ? null : sender.substring(sender.indexOf('@') + 1);
                NewReport report = analyse(service, Letters.fixture(fixture), sender, domain);
                assertThat(report.results()).allSatisfy(result -> assertThat(result.explanation().toLowerCase())
                        .doesNotContain("verified", "safe", "legit", "guaranteed"));
            }
        }
    }

    // --- P7: the AI extraction (PRD §8, R-A) ---

    @Test
    void aiEmpty_partialAiUsedFalse() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));
        when(extraction.extract(any())).thenReturn(Optional.empty());

        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)), Letters.fixture(Letters.GENUINE),
                "talent@northwind-analytics.com", "northwind-analytics.com");

        assertThat(report.aiUsed()).isFalse();
        assertThat(report.partial()).as("PRD §2.1 pillar 4: AI down → the report still arrives, PARTIAL").isTrue();
        assertThat(report.aiDetails().isEmpty()).isTrue();
        assertThat(report.score().band()).isEqualTo(Band.LOW);
        assertThat(report.results()).allSatisfy(result -> assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED));
    }

    @Test
    void aiUsed_notPartial_detailsKept() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));
        when(extraction.extract(any())).thenReturn(Optional.of(new Extraction("Northwind Analytics", null,
                "9,00,000 per annum", null, List.of(), List.of(), List.of(), "openai/gpt-oss-120b", false)));

        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)), Letters.fixture(Letters.GENUINE),
                "talent@northwind-analytics.com", "northwind-analytics.com");

        assertThat(report.aiUsed()).isTrue();
        assertThat(report.partial()).isFalse();
        assertThat(report.aiDetails()).isEqualTo(new AiDetails("Northwind Analytics", null, "9,00,000 per annum", null));
        assertThat(report.score().band()).isEqualTo(Band.LOW);
    }

    @Test
    void aiPaymentMention_addsC1LabelledAiExtracted() {
        String letter = "Dear Candidate,\n\nThe deposit\n\nwill be Rs. 3,000 before joining.\n";
        when(extraction.extract(any())).thenReturn(Optional.of(new Extraction(null, null, null, null, List.of(),
                List.of(), List.of("The deposit will be Rs. 3,000 before joining."), "openai/gpt-oss-120b", false)));

        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)), letter, null, null);

        CheckResult moneyRequest = result(report, CheckCode.C1);
        assertThat(moneyRequest.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(moneyRequest.aiExtracted()).isTrue();
        assertThat(report.score().band()).isEqualTo(Band.HIGH);
    }

    @Test
    void extractionThrows_reportStillArrives() {
        when(extraction.extract(any())).thenThrow(new IllegalStateException("SECRET-AI-FAILURE"));

        NewReport report;
        try (LogCapture logs = LogCapture.of(AnalysisService.class)) {
            report = analyse(service(AnalysisFixtures.checks(rdap, dns)), Letters.fixture(Letters.DEPOSIT_SCAM),
                    null, null);
            assertThat(logs.events()).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("SECRET-AI-FAILURE"));
        }

        assertThat(report.aiUsed()).isFalse();
        assertThat(report.partial()).isTrue();
        assertThat(report.score().band()).isEqualTo(Band.HIGH);
    }

    // --- P8: known companies (C3, C4) ---

    @Test
    void inf0sysCareersSender_lookalikeFlagged() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));

        NewReport report = analyse(service(withKnownCompanies(KnownCompanyFixtures.company("Infosys", "infosys.com"))),
                "Dear Candidate, we are pleased to offer you the role of Trainee Analyst. Regards, HR Team",
                "hr@inf0sys-careers.com", "inf0sys-careers.com");

        assertThat(report.results()).extracting(CheckResult::code).containsExactly(CheckCode.C1, CheckCode.C2,
                CheckCode.C3, CheckCode.C4, CheckCode.C5, CheckCode.C6, CheckCode.C8, CheckCode.C9);
        CheckResult lookalike = result(report, CheckCode.C4);
        assertThat(lookalike.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(lookalike.points()).isEqualTo(30);
        assertThat(result(report, CheckCode.C3).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(report.score().points()).isEqualTo(30);
        assertThat(report.score().band()).isEqualTo(Band.MEDIUM);
        assertThat(report.partial()).isFalse();
    }

    @Test
    void aiCompanyName_reachesC3_onlyAddsRisk() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));
        when(extraction.extract(any())).thenReturn(Optional.of(new Extraction("Zenvora Ltd", null, null, null,
                List.of(), List.of(), List.of(), "openai/gpt-oss-120b", false)));

        NewReport report = analyse(service(withKnownCompanies(KnownCompanyFixtures.company("Zenvora", "zenvora.com"))),
                "Dear Candidate, welcome aboard. Regards, HR Team", "hr@young-firm.com", "young-firm.com");

        CheckResult mismatch = result(report, CheckCode.C3);
        assertThat(mismatch.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(mismatch.aiExtracted()).isTrue();
        assertThat(report.score().points()).isEqualTo(20);
    }

    @Test
    void confirmedPhoneInRegistry_highByC10_keysSavedWithReport() {
        IndicatorHasher hasher = new IndicatorHasher("analysis-service-test-pepper-" + "z".repeat(10));
        String key = hasher.phone("+91 99999 01234").orElseThrow();
        MatchService matches = mock(MatchService.class);
        when(matches.match(any())).thenReturn(new RegistryMatch(List.of(new MatchedMarker(java.util.UUID.randomUUID(),
                IndicatorType.PHONE, IndicatorStatus.CONFIRMED, key, null)), 0));

        NewReport report = analyse(service(withRegistryChecks(),
                        new RegistryLookup(hasher, matches, AnalysisFixtures.noKnownCompanies())),
                "Dear Candidate, please call our HR desk on +91 99999 01234 to complete joining.", null, null);

        assertThat(report.results()).extracting(CheckResult::code).containsExactly(CheckCode.C1, CheckCode.C2,
                CheckCode.C5, CheckCode.C6, CheckCode.C8, CheckCode.C9, CheckCode.C10, CheckCode.C11, CheckCode.C12);
        assertThat(result(report, CheckCode.C10).hardOverride()).isTrue();
        assertThat(result(report, CheckCode.C11).outcome()).as("under 50 words").isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result(report, CheckCode.C12).outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(report.score().band()).isEqualTo(Band.HIGH);
        assertThat(report.markers().phoneHmacs()).containsExactly(key);
        assertThat(report.markers().complete()).isTrue();
        assertThat(report.reportedBy()).isZero();
    }

    @Test
    void letterSourcedSender_reportKeepsSourceAndEmailDomainCount() {
        // P13 (S2): no typed sender email, so D-33.6 took the first address in the letter.
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));

        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)), "Dear Candidate, write to "
                + "talent@northwind-analytics.com or to hr@careers.northwind-analytics.com, or send the documents to "
                + "joining.desk@gmail.com.", null, "northwind-analytics.com");

        assertThat(report.senderSource()).isEqualTo(com.offershield.common.SenderSource.LETTER);
        assertThat(report.letterEmailDomains()).as("registrable domains: northwind-analytics.com, gmail.com")
                .isEqualTo(2);
    }

    @Test
    void typedSender_reportKeepsTyped() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));

        NewReport report = analyse(service(AnalysisFixtures.checks(rdap, dns)), "Dear Candidate, welcome aboard.",
                "talent@northwind-analytics.com", "northwind-analytics.com");

        assertThat(report.senderSource()).isEqualTo(com.offershield.common.SenderSource.TYPED);
        assertThat(report.letterEmailDomains()).isZero();
    }

    @Test
    void wordingOnlyReports_officialSenderOfNamedCompany_c12PassedReportedByZero() {
        // P13 (test gap, C12 fairness): unverified wording-only reports don't count for the named company's official
        // sender (PRD §7.3 guard, by rules), so a genuine letter of a known company is not pushed to MEDIUM.
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(Instant.now().minus(Duration.ofDays(4000))));
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));
        MatchService matches = mock(MatchService.class);
        when(matches.match(any())).thenReturn(new RegistryMatch(List.of(new MatchedMarker(java.util.UUID.randomUUID(),
                IndicatorType.SIMHASH, IndicatorStatus.UNVERIFIED, "00000000000000ff", 1)), 3, 0));
        CopiedLetterGuard northwind = new CopiedLetterGuard(KnownCompanyFixtures.knownCompanies(
                KnownCompanyFixtures.company("Northwind Analytics", "northwind-analytics.com")), new FreeMailDomains());
        RegistryLookup lookup = new RegistryLookup(new IndicatorHasher("analysis-service-test-pepper-" + "z".repeat(10)),
                matches, northwind);

        NewReport report = analyse(service(withRegistryChecks(), lookup), Letters.fixture(Letters.GENUINE),
                "talent@northwind-analytics.com", "northwind-analytics.com");

        assertThat(result(report, CheckCode.C12).outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(report.reportedBy()).isZero();
        assertThat(report.score().band()).isEqualTo(Band.LOW);
    }

    @Test
    void registryDown_c10c11c12Unavailable_reportStillSavedPartial() {
        MatchService matches = mock(MatchService.class);
        when(matches.match(any())).thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
        IndicatorHasher hasher = new IndicatorHasher("analysis-service-test-pepper-" + "z".repeat(10));

        NewReport report = analyse(service(withRegistryChecks(),
                        new RegistryLookup(hasher, matches, AnalysisFixtures.noKnownCompanies())),
                Letters.fixture(Letters.GENUINE), null, null);

        assertThat(report.results()).filteredOn(result -> result.code().sourceKind() >= 4)
                .extracting(CheckResult::outcome).containsOnly(CheckOutcome.UNAVAILABLE);
        assertThat(report.partial()).isTrue();
        assertThat(report.markers().simhash()).isNotNull();
    }

    private List<RiskCheck> withRegistryChecks() {
        List<RiskCheck> checks = new ArrayList<>(AnalysisFixtures.checks(rdap, dns));
        checks.add(new ConfirmedIndicatorCheck());
        checks.add(new NearDuplicateCheck(new CopiedLetterGuard(KnownCompanyFixtures.knownCompanies(),
                new FreeMailDomains())));
        checks.add(new UnverifiedReportsCheck());
        return checks;
    }

    private AnalysisService service(List<RiskCheck> checks, RegistryLookup registry) {
        return new AnalysisService(submissions, new DomainValidator(), checks, new ScoringEngine(), reports,
                mock(ProcessedEvents.class), publisher, extraction, new ExtractionMerger(), registry);
    }

    private List<RiskCheck> withKnownCompanies(KnownCompanyDirectory.Company... companies) {
        KnownCompanies known = KnownCompanyFixtures.knownCompanies(companies);
        List<RiskCheck> checks = new ArrayList<>(AnalysisFixtures.checks(rdap, dns));
        checks.add(new CompanyDomainMismatchCheck(known, new FreeMailDomains()));
        checks.add(new LookalikeDomainCheck(known, new FreeMailDomains()));
        return checks;
    }

    private AnalysisService service(List<RiskCheck> checks) {
        return new AnalysisService(submissions, new DomainValidator(), checks, new ScoringEngine(), reports,
                mock(ProcessedEvents.class), publisher, extraction, new ExtractionMerger(),
                AnalysisFixtures.registryFindingNothing());
    }

    private NewReport analyse(AnalysisService service, String text, String senderEmail, String senderDomain) {
        org.mockito.Mockito.reset(reports, publisher);
        when(submissions.findContent(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(
                AnalysisFixtures.content(text, senderEmail, senderDomain)));
        when(reports.save(any(), any())).thenReturn(true);
        when(reports.findCompletion(AnalysisFixtures.SUBMISSION)).thenReturn(Optional.of(AnalysisFixtures.completion()));
        when(publisher.publish(any())).thenReturn(true);

        service.analyse(AnalysisFixtures.event());

        ArgumentCaptor<NewReport> saved = ArgumentCaptor.forClass(NewReport.class);
        verify(reports).save(eq(AnalysisFixtures.event().eventId()), saved.capture());
        return saved.getValue();
    }

    private static CheckResult result(NewReport report, CheckCode code) {
        return report.results().stream().filter(result -> result.code() == code).findFirst().orElseThrow();
    }
}
