package com.offershield.analysis.checks;

import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.Score;
import com.offershield.analysis.scoring.ScoringEngine;
import com.offershield.common.CopyRules;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.RegistryMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static com.offershield.analysis.checks.RegistryFixtures.PHONE_KEY;
import static com.offershield.analysis.checks.RegistryFixtures.found;
import static com.offershield.analysis.checks.RegistryFixtures.letter;
import static com.offershield.analysis.checks.RegistryFixtures.marker;
import static com.offershield.analysis.checks.RegistryFixtures.wording;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C12, unverified user reports (PRD §7.1, §7.2, §7.3; R-B, D-33.5): 3 or more distinct reporters +25, 1–2 +10, shown
 * as "Reported as a scam by N users (not verified)", source kind 5. Unverified reports never force High on their own.
 */
class UnverifiedReportsCheckTest {

    private final UnverifiedReportsCheck check = new UnverifiedReportsCheck();

    @ParameterizedTest
    @CsvSource({"1, 10, Reported as a scam by 1 user (not verified).",
            "2, 10, Reported as a scam by 2 users (not verified).",
            "3, 25, Reported as a scam by 3 users (not verified).",
            "7, 25, Reported as a scam by 7 users (not verified)."})
    void tiers(int reporters, int points, String label) {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(reporters,
                marker(IndicatorType.PHONE, IndicatorStatus.UNVERIFIED, PHONE_KEY))));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(points);
        assertThat(result.hardOverride()).isFalse();
        assertThat(result.sourceKind()).isEqualTo(5);
        assertThat(result.explanation()).startsWith(label);
        assertThat(CopyRules.violates(result.explanation())).isFalse();
        assertThat(result.evidence()).contains("a phone number").doesNotContain(PHONE_KEY);
    }

    @Test
    void manyReporters_neverOverride() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(50,
                marker(IndicatorType.DOMAIN, IndicatorStatus.UNVERIFIED, "scam-firm.com"),
                wording(IndicatorStatus.UNVERIFIED, 0))));

        Score score = new ScoringEngine().score(List.of(result));

        assertThat(score.hardOverride()).isFalse();
        assertThat(score.points()).isEqualTo(25);
        assertThat(score.band()).isEqualTo(Band.MEDIUM);
        assertThat(result.evidence()).contains("scam-firm.com", "the letter's wording");
    }

    @Test
    void noReporters_passed() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(0,
                marker(IndicatorType.PHONE, IndicatorStatus.CONFIRMED, PHONE_KEY))));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.points()).isZero();
    }

    @Test
    void reportsOnlyThroughAiFoundPhone_labelledAiExtracted() {
        RegistryFindings findings = RegistryFindings.checked(true, false, new RegistryMatch(List.of(
                marker(IndicatorType.PHONE, IndicatorStatus.UNVERIFIED, PHONE_KEY)), 1), Set.of(PHONE_KEY));

        CheckResult result = check.evaluate(letter("Dear Candidate.", findings));

        assertThat(result.aiExtracted()).isTrue();
        assertThat(result.explanation()).startsWith("AI-extracted: Reported as a scam by 1 user (not verified).");
    }

    @Test
    void nothingToCompare_notApplicable() {
        assertThat(check.evaluate(Letters.text("Pay now.")).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void registryDown_unavailable() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", RegistryFindings.unavailable(true, false)));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.UNAVAILABLE);
        assertThat(result.sourceKind()).isEqualTo(5);
    }
}
