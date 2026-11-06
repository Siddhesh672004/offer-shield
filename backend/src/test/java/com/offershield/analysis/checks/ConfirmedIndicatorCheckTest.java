package com.offershield.analysis.checks;

import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.ScoringEngine;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.RegistryMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.offershield.analysis.checks.RegistryFixtures.PHONE_KEY;
import static com.offershield.analysis.checks.RegistryFixtures.UPI_KEY;
import static com.offershield.analysis.checks.RegistryFixtures.found;
import static com.offershield.analysis.checks.RegistryFixtures.letter;
import static com.offershield.analysis.checks.RegistryFixtures.marker;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C10, confirmed registry match (PRD §7.1, §7.2; R-B): a sender domain, phone number or UPI ID that a TPO or the admin
 * confirmed as a scam forces High (source kind 4). An UNVERIFIED match never does: it only feeds C12.
 */
class ConfirmedIndicatorCheckTest {

    private final ConfirmedIndicatorCheck check = new ConfirmedIndicatorCheck();

    @Test
    void unverifiedMatch_noOverride() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(3,
                marker(IndicatorType.PHONE, IndicatorStatus.UNVERIFIED, PHONE_KEY),
                marker(IndicatorType.DOMAIN, IndicatorStatus.UNVERIFIED, "scam-firm.com"))));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.hardOverride()).isFalse();
        assertThat(new ScoringEngine().score(List.of(result)).band()).isEqualTo(Band.LOW);
    }

    @Test
    void confirmedPhone_overrideHighSourceKind4() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(0,
                marker(IndicatorType.PHONE, IndicatorStatus.CONFIRMED, PHONE_KEY))));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.hardOverride()).isTrue();
        assertThat(result.points()).isZero();
        assertThat(result.sourceKind()).isEqualTo(4);
        assertThat(result.aiExtracted()).isFalse();
        assertThat(result.explanation()).contains("confirmed").doesNotStartWith("AI-extracted");
        assertThat(result.evidence()).contains("a phone number").doesNotContain(PHONE_KEY);
        assertThat(new ScoringEngine().score(List.of(result)).band()).isEqualTo(Band.HIGH);
    }

    @Test
    void confirmedDomainAndUpi_evidenceNamesDomainNeverKeys() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(0,
                marker(IndicatorType.DOMAIN, IndicatorStatus.CONFIRMED, "scam-firm.com"),
                marker(IndicatorType.UPI, IndicatorStatus.CONFIRMED, UPI_KEY))));

        assertThat(result.hardOverride()).isTrue();
        assertThat(result.evidence()).contains("scam-firm.com", "a UPI ID").doesNotContain(UPI_KEY);
    }

    @Test
    void confirmedWordingOnly_notC10() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(0,
                RegistryFixtures.wording(IndicatorStatus.CONFIRMED, 1))));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
    }

    @Test
    void matchOnlyThroughAiFoundPhone_labelledAiExtracted() {
        RegistryFindings findings = RegistryFindings.checked(true, false, new RegistryMatch(List.of(
                marker(IndicatorType.PHONE, IndicatorStatus.CONFIRMED, PHONE_KEY)), 0), Set.of(PHONE_KEY));

        CheckResult result = check.evaluate(letter("Dear Candidate.", findings));

        assertThat(result.hardOverride()).isTrue();
        assertThat(result.aiExtracted()).isTrue();
        assertThat(result.explanation()).startsWith("AI-extracted:");
    }

    @Test
    void nothingToCompare_notApplicable() {
        CheckResult result = check.evaluate(Letters.text("Dear Candidate."));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
    }

    @Test
    void registryDown_unavailable() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", RegistryFindings.unavailable(true, true)));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.UNAVAILABLE);
        assertThat(result.sourceKind()).isEqualTo(4);
    }
}
