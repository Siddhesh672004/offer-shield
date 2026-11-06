package com.offershield.analysis.checks;

import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import com.offershield.company.KnownCompanyDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.offershield.analysis.checks.KnownCompanyFixtures.company;
import static com.offershield.analysis.checks.KnownCompanyFixtures.knownCompanies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4, look-alike domain (PRD §7.1, §7.2 +30; D-03 brand rule; D-08). The PRD's done-when: {@code inf0sys-careers.com}
 * is flagged. Known companies are test fixtures only (no seed, V-21).
 */
class LookalikeDomainCheckTest {

    private static final String LETTER = "Dear Candidate, you are selected. Regards, HR Team";

    private final LookalikeDomainCheck check = new LookalikeDomainCheck(knownCompanies(
            company("Infosys", "infosys.com"),
            company("Accenture", "accenture.com"),
            company("Northwind Analytics", "northwind-analytics.com"),
            company("Zenvora Labs Private Limited", "zvl-group.com"),
            company("TCS", "tcs.com"),
            company("Infosys BPM", "infosysbpm.com")), new FreeMailDomains());

    @Test
    void inf0sysCareers_flagged() {
        CheckResult result = check.evaluate(Letters.from("hr@inf0sys-careers.com", LETTER));

        assertThat(result.code()).isEqualTo(CheckCode.C4);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(30);
        assertThat(result.hardOverride()).isFalse();
        assertThat(result.sourceKind()).isEqualTo(3);
        assertThat(result.aiExtracted()).isFalse();
        assertThat(result.explanation()).contains("Infosys");
        assertThat(result.evidence()).contains("inf0sys-careers.com", "Infosys", "infosys.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hr@hr.inf0sys-careers.co.in", "hr@lnfosys.com", "hr@1nfosys.in", "hr@INFOSYS-HR.COM",
            "hr@infosis.com" /* one changed letter */, "hr@infossys.com" /* one extra letter */,
            "hr@infsoys.com" /* two letters swapped */, "hr@infosys.hr-portal.com" /* brand as a subdomain */,
            "hr@infosys-careers.github.io" /* private suffix */, "hr@infosys.co.in" /* not listed as official */})
    void lnfosys_flagged(String sender) {
        CheckResult result = check.evaluate(Letters.from(sender, LETTER));

        assertThat(result.outcome()).as(sender).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.evidence()).contains("Infosys");
    }

    @Test
    void cyrillicHomoglyph_flagged() {
        CheckResult result = check.evaluate(Letters.from("hr@іnfosys.com", LETTER)); // Cyrillic і

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.evidence()).as("shows what the student saw and the punycode form")
                .contains("іnfosys.com", "xn--");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hr@infosys.com", "talent@careers.infosys.com", "hr@INFOSYS.COM"})
    void official_notFlagged(String sender) {
        CheckResult result = check.evaluate(Letters.from(sender, LETTER));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.points()).isZero();
        assertThat(result.explanation()).contains("official");
    }

    @Test
    void officialDomainOfAnotherCompany_notFlagged() {
        // D-08: infosysbpm.com contains "infosys", but it is listed as Infosys BPM's own official domain.
        assertThat(check.evaluate(Letters.from("hr@infosysbpm.com", LETTER)).outcome()).isEqualTo(CheckOutcome.PASSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"talent@acme-analytics.com", "hr@young-firm.com", "hr@infotech-systems.in",
            "hr@imfosis.com" /* two edits from a 7-letter brand */, "hr@infos-hub.com", "hr@sys-info.com"})
    void unrelated_notFlagged(String sender) {
        CheckResult result = check.evaluate(Letters.from(sender, LETTER));

        assertThat(result.outcome()).as(sender).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.points()).isZero();
    }

    @Test
    void longBrand_twoEdits_flagged() {
        // Brands of 8+ characters allow up to 2 edits (D-03); shorter ones allow 1.
        assertThat(check.evaluate(Letters.from("hr@akcentyre-jobs.com", LETTER)).outcome())
                .isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(check.evaluate(Letters.from("hr@akcentyrr-jobs.com", LETTER)).outcome())
                .as("three edits").isEqualTo(CheckOutcome.PASSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hr@tcs-careers.com", "hr@tcs.co.in", "hr@tc5.com", "hr@abtcsxyz.com"})
    void shortBrand_ignored(String sender) {
        // Brands under 5 characters (TCS) are not used for look-alikes, so unrelated firms are not flagged; C3 still
        // flags a letter that names TCS from another domain.
        assertThat(check.evaluate(Letters.from(sender, LETTER)).outcome()).as(sender).isEqualTo(CheckOutcome.PASSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hr@n0rthwind-analytics.com", "hr@northwindanalytics-careers.in",
            "hr@zenvora-labs.com" /* the list name without "Private Limited" */, "hr@zvl-gr0up.com"})
    void multiWordNameAndDomainLabel_flagged(String sender) {
        assertThat(check.evaluate(Letters.from(sender, LETTER)).outcome()).as(sender).isEqualTo(CheckOutcome.TRIGGERED);
    }

    @Test
    void domainContainingBrand_flaggedPerD03() {
        // Documented limitation (D-03 "contains"): an unrelated name that contains a brand is flagged too (+30 alone is
        // Medium, never High); the admin can list such a firm's domain if it is a real employer.
        assertThat(check.evaluate(Letters.from("hr@xyzinfosystems.com", LETTER)).outcome())
                .isEqualTo(CheckOutcome.TRIGGERED);
    }

    @Test
    void freeMailSender_notApplicable() {
        CheckResult result = check.evaluate(Letters.from("infosys.careers@gmail.com", LETTER));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result.points()).isZero();
    }

    @Test
    void noPublicSenderDomain_notApplicable() {
        assertThat(check.evaluate(Letters.text(LETTER)).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(check.evaluate(Letters.from("x@127.0.0.1", LETTER)).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void emptyList_notApplicable() {
        KnownCompanies none = mock(KnownCompanies.class);
        when(none.directory()).thenReturn(KnownCompanyDirectory.EMPTY);

        CheckResult result = new LookalikeDomainCheck(none, new FreeMailDomains())
                .evaluate(Letters.from("hr@inf0sys-careers.com", LETTER));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void explanations_neverClaimSafety() {
        for (String sender : new String[]{"hr@inf0sys-careers.com", "hr@infosys.com", "hr@young-firm.com",
                "a@gmail.com"}) {
            assertThat(check.evaluate(Letters.from(sender, LETTER)).explanation().toLowerCase())
                    .doesNotContain("verified", "safe", "legit", "guarantee");
        }
    }
}
