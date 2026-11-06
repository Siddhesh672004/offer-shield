package com.offershield.analysis.checks;

import com.offershield.common.FreeMailDomains;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** C8 (PRD §7.1): only a WhatsApp/Telegram contact and no company email adds 15 points. */
class OffPlatformContactCheckTest {

    private final OffPlatformContactCheck check = new OffPlatformContactCheck(new FreeMailDomains());

    @Test
    void whatsappOnly_triggered15_phoneMasked() {
        CheckResult result = check.evaluate(Letters.text(Letters.fixture(Letters.WHATSAPP_ONLY)));

        assertThat(result.code()).isEqualTo(CheckCode.C8);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(15);
        assertThat(result.sourceKind()).isEqualTo(1);
        assertThat(result.evidence()).contains("WhatsApp", "[phone]").doesNotContain("99999");
    }

    @Test
    void telegramWithFreeMailSender_triggered() {
        assertThat(check.evaluate(Letters.from("jobs.desk@gmail.com", "Join our Telegram group t.me/examplejobs for the next steps.")).outcome())
                .isEqualTo(CheckOutcome.TRIGGERED);
    }

    @Test
    void whatsappPlusCompanyEmail_passed() {
        CheckResult result = check.evaluate(Letters.text(
                "You can reach HR on WhatsApp or by email at talent@northwind-analytics.com."));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.points()).isZero();
    }

    @Test
    void whatsappWithCompanySender_passed() {
        assertThat(check.evaluate(Letters.from("talent@northwind-analytics.com", "Ping us on WhatsApp for queries.")).outcome())
                .isEqualTo(CheckOutcome.PASSED);
    }

    @Test
    void noMessengerMention_passed() {
        assertThat(check.evaluate(Letters.text(Letters.fixture(Letters.GENUINE))).outcome()).isEqualTo(CheckOutcome.PASSED);
    }
}
