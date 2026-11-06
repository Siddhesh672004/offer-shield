package com.offershield.analysis.checks;

import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import com.offershield.company.KnownCompanyDirectory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.offershield.analysis.checks.KnownCompanyFixtures.company;
import static com.offershield.analysis.checks.KnownCompanyFixtures.knownCompanies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C3, the sender's domain is not the claimed company's official domain (PRD §7.1, §7.2 +20; source kind 3). The claimed
 * company comes from the rules (a known company's name in the letter) and, only when the rules find none, from the AI's
 * company name, which may only add risk (R-A). Free-mail senders are left to C2 (no double counting). Fictional
 * companies only (05 §4).
 */
class CompanyDomainMismatchCheckTest {

    private static final String NORTHWIND_LETTER = Letters.fixture(Letters.GENUINE); // names "Northwind Analytics"

    private final CompanyDomainMismatchCheck check = new CompanyDomainMismatchCheck(knownCompanies(
            company("Northwind Analytics Private Limited", "northwind-analytics.com", "northwind.co.in"),
            company("Zenvora", "zenvora.com"),
            company("TCS", "tcs.com")), new FreeMailDomains());

    @Test
    void claimedCompanyFromOtherDomain_triggered20() {
        CheckResult result = check.evaluate(Letters.from("hr@n0rthwind-analytics.com", NORTHWIND_LETTER));

        assertThat(result.code()).isEqualTo(CheckCode.C3);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(20);
        assertThat(result.hardOverride()).isFalse();
        assertThat(result.sourceKind()).isEqualTo(3);
        assertThat(result.aiExtracted()).isFalse();
        assertThat(result.explanation()).contains("Northwind Analytics Private Limited");
        assertThat(result.evidence()).contains("Northwind Analytics Private Limited", "n0rthwind-analytics.com",
                "northwind-analytics.com", "northwind.co.in");
    }

    @Test
    void unrelatedSenderDomain_triggered() {
        assertThat(check.evaluate(Letters.from("jobs@young-firm.com", NORTHWIND_LETTER)).points()).isEqualTo(20);
    }

    @Test
    void shortNameWholeWord_triggered() {
        // C4 ignores short brands; C3 still catches a letter that names one from another domain.
        CheckResult result = check.evaluate(Letters.from("hr@tcs-careers.com",
                "Dear Candidate, you have been selected by TCS for the role of Analyst."));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.evidence()).contains("TCS");
    }

    @Test
    void officialSender_passed() {
        for (String sender : new String[]{"talent@northwind-analytics.com", "talent@mail.northwind-analytics.com",
                "hr@northwind.co.in"}) {
            CheckResult result = check.evaluate(Letters.from(sender, NORTHWIND_LETTER));

            assertThat(result.outcome()).as(sender).isEqualTo(CheckOutcome.PASSED);
            assertThat(result.points()).isZero();
            assertThat(result.aiExtracted()).isFalse();
        }
    }

    @Test
    void nameMatching_caseAndLineBreaksIgnored_legalSuffixOptional() {
        String letter = "Welcome to NORTHWIND\nANALYTICS. We look forward to your joining.";

        assertThat(check.evaluate(Letters.from("hr@young-firm.com", letter)).outcome())
                .isEqualTo(CheckOutcome.TRIGGERED);
    }

    @Test
    void nameInsideAnotherWord_notNamed() {
        String letter = "Dear Candidate, welcome to Zenvorabank and the TCSL group.";

        assertThat(check.evaluate(Letters.from("hr@young-firm.com", letter)).outcome())
                .isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void severalNamed_senderOfficialForOne_passed() {
        String letter = "Zenvora, a partner of Northwind Analytics, offers you the role of Analyst.";

        assertThat(check.evaluate(Letters.from("hr@zenvora.com", letter)).outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(check.evaluate(Letters.from("hr@young-firm.com", letter)).evidence())
                .contains("Northwind Analytics Private Limited", "Zenvora");
    }

    @Test
    void noKnownCompanyNamed_notApplicable() {
        CheckResult result = check.evaluate(Letters.from("hr@young-firm.com", Letters.fixture(Letters.DEPOSIT_SCAM)));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result.points()).isZero();
    }

    @Test
    void freeMailSender_notApplicable_noDoubleCountWithC2() {
        CheckResult result = check.evaluate(Letters.from("northwind.hr@gmail.com", NORTHWIND_LETTER));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result.explanation()).contains("C2");
    }

    @Test
    void noPublicSenderDomain_notApplicable() {
        assertThat(check.evaluate(Letters.text(NORTHWIND_LETTER)).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(check.evaluate(Letters.from("x@127.0.0.1", NORTHWIND_LETTER)).outcome())
                .isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void emptyList_notApplicable() {
        KnownCompanies none = mock(KnownCompanies.class);
        when(none.directory()).thenReturn(KnownCompanyDirectory.EMPTY);

        assertThat(new CompanyDomainMismatchCheck(none, new FreeMailDomains())
                .evaluate(Letters.from("hr@young-firm.com", NORTHWIND_LETTER)).outcome())
                .isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    // --- R-A: the AI's company name only fills the gap and only adds risk ---

    @Test
    void aiCompanyOnly_otherDomain_triggeredLabelledAiExtracted() {
        Letter letter = Letters.from("hr@young-firm.com", "Dear Candidate, welcome aboard.").withAi(ai("Zenvora Ltd"));

        CheckResult result = check.evaluate(letter);

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(20);
        assertThat(result.aiExtracted()).isTrue();
        assertThat(result.explanation()).startsWith("AI-extracted:");
        assertThat(result.evidence()).contains("Zenvora");
    }

    @Test
    void aiCompanyOnly_officialSender_neverPassed() {
        // The AI alone never identifies the sender as the company's official domain (R-A, P9's copied-letter guard).
        Letter letter = Letters.from("hr@zenvora.com", "Dear Candidate, welcome aboard.").withAi(ai("Zenvora"));

        CheckResult result = check.evaluate(letter);

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result.aiExtracted()).isFalse();
    }

    @Test
    void rulesFoundCompany_aiNameIgnored() {
        Letter official = Letters.from("talent@northwind-analytics.com", NORTHWIND_LETTER).withAi(ai("Zenvora"));
        Letter mismatch = Letters.from("hr@young-firm.com", NORTHWIND_LETTER).withAi(ai("Zenvora"));

        assertThat(check.evaluate(official).outcome()).isEqualTo(CheckOutcome.PASSED);
        CheckResult result = check.evaluate(mismatch);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.aiExtracted()).isFalse();
    }

    @Test
    void aiUnknownCompany_notApplicable() {
        Letter letter = Letters.from("hr@young-firm.com", "Dear Candidate.").withAi(ai("Example Global Solutions"));

        assertThat(check.evaluate(letter).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void explanations_neverClaimSafety() {
        for (String sender : new String[]{"hr@young-firm.com", "talent@northwind-analytics.com", "a@gmail.com"}) {
            assertThat(check.evaluate(Letters.from(sender, NORTHWIND_LETTER)).explanation().toLowerCase())
                    .doesNotContain("verified", "safe", "legit", "guarantee");
        }
    }

    @Test
    void letterSourcedOfficialSender_notApplicableNeverPassed() {
        // P13 (S2): the letter's author chose that address, so it can't show the letter came from the company.
        CheckResult result = check.evaluate(Letters.fromLetter("Northwind Analytics is pleased to offer you the role "
                + "of Analyst. Write to talent@northwind-analytics.com to accept."));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result.points()).isZero();
        assertThat(result.explanation()).contains("taken from the letter itself");
        assertThat(result.explanation().toLowerCase()).doesNotContain("verified", "safe", "legit", "guarantee");
    }

    @Test
    void letterSourcedOtherDomain_stillTriggered() {
        CheckResult result = check.evaluate(Letters.fromLetter("Northwind Analytics is pleased to offer you the role "
                + "of Analyst. Write to hr@n0rthwind-analytics.com to accept."));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(20);
    }

    private static AiFindings ai(String companyName) {
        return new AiFindings(List.of(), companyName, List.of(), List.of());
    }
}
