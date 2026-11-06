package com.offershield.analysis.checks;

import com.offershield.common.FreeMailDomains;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** C2 (PRD §7.1, §7.2): a free-mail sender adds 25 points; source kind 1 (the address is in the letter). */
class FreeMailSenderCheckTest {

    private final FreeMailSenderCheck check = new FreeMailSenderCheck(new FreeMailDomains());

    @Test
    void gmailSender_triggered25() {
        CheckResult result = check.evaluate(Letters.from("hr.example.jobs@gmail.com", "Dear Candidate"));

        assertThat(result.code()).isEqualTo(CheckCode.C2);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(25);
        assertThat(result.hardOverride()).isFalse();
        assertThat(result.sourceKind()).isEqualTo(1);
        assertThat(result.evidence()).isEqualTo("Sender address ends in @gmail.com").doesNotContain("hr.example.jobs");
    }

    @Test
    void upperCaseIndianProvider_triggered() {
        assertThat(check.evaluate(Letters.from("Recruiter@YAHOO.CO.IN", "Dear Candidate")).outcome())
                .isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(check.evaluate(Letters.from("hr@rediffmail.com", "Dear Candidate")).points()).isEqualTo(25);
    }

    @Test
    void companyDomain_passed() {
        CheckResult result = check.evaluate(Letters.from("talent@northwind-analytics.com", "Dear Candidate"));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.points()).isZero();
    }

    @Test
    void noSender_notApplicable() {
        assertThat(check.evaluate(Letters.text("Dear Candidate")).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void freeMailList_loadedWithCommonProviders() {
        FreeMailDomains domains = new FreeMailDomains();

        assertThat(domains.size()).isGreaterThan(40);
        assertThat(domains.contains("gmail.com")).isTrue();
        assertThat(domains.contains("Outlook.com.")).isTrue();
        assertThat(domains.contains("infosys.com")).isFalse();
        assertThat(domains.contains(null)).isFalse();
    }
}
