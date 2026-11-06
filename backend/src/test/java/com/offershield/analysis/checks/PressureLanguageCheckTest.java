package com.offershield.analysis.checks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** C9 (PRD §7.1, §7.2): +10 for each pressure phrase, at most two. */
class PressureLanguageCheckTest {

    private final PressureLanguageCheck check = new PressureLanguageCheck();

    @Test
    void onePhrase_10() {
        CheckResult result = check.evaluate(Letters.text("Please accept this offer within 24 hours."));

        assertThat(result.code()).isEqualTo(CheckCode.C9);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(10);
        assertThat(result.sourceKind()).isEqualTo(1);
        assertThat(result.evidence()).isEqualTo("“within 24 hours”");
    }

    @Test
    void cappedAtTwo() {
        CheckResult result = check.evaluate(Letters.text(Letters.fixture(Letters.PRESSURE)));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(20);
        assertThat(result.evidence()).contains("no interview required", "Limited seats", "within 24 hours");
    }

    @Test
    void samePhraseTwice_countsOnce() {
        assertThat(check.evaluate(Letters.text("Limited seats! Hurry, limited seats.")).points()).isEqualTo(20);
        assertThat(check.evaluate(Letters.text("Limited seats. Only limited seats.")).points()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Jaldi karein, seats limited hain.",
            "Aaj hi payment karein.",
            "Selected without any interview.",
            "First come, first served.",
            "Offer expires today."})
    void hinglishAndOtherPhrases_triggered(String text) {
        assertThat(check.evaluate(Letters.text(text)).outcome()).isEqualTo(CheckOutcome.TRIGGERED);
    }

    @Test
    void genuineDeadline_passed() {
        CheckResult result = check.evaluate(Letters.text(Letters.fixture(Letters.GENUINE)));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.points()).isZero();
        assertThat(result.evidence()).isNull();
    }
}
