package com.offershield.analysis.checks;

import com.offershield.analysis.scoring.ScoringEngine;
import com.offershield.common.FreeMailDomains;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.RegistryMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.offershield.analysis.checks.RegistryFixtures.found;
import static com.offershield.analysis.checks.RegistryFixtures.letter;
import static com.offershield.analysis.checks.RegistryFixtures.wording;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C11, near-duplicate of a confirmed scam letter (PRD §7.1, §10.1; R-B): only a CONFIRMED SimHash match within 3 bits
 * counts; an UNVERIFIED one never forces High (it feeds C12). Short letters have no fingerprint. The copied-letter
 * guard itself is pinned in {@link CopiedLetterGuardTest}.
 */
class NearDuplicateCheckTest {

    private final NearDuplicateCheck check = new NearDuplicateCheck(new CopiedLetterGuard(
            KnownCompanyFixtures.knownCompanies(), new FreeMailDomains()));

    @Test
    void unverifiedMatch_noOverride() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(5, wording(IndicatorStatus.UNVERIFIED, 0))));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.hardOverride()).isFalse();
        assertThat(new ScoringEngine().score(List.of(result)).hardOverride()).isFalse();
    }

    @Test
    void confirmedMatch_overrideWithDistanceEvidence() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(0, wording(IndicatorStatus.CONFIRMED, 2))));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.hardOverride()).isTrue();
        assertThat(result.sourceKind()).isEqualTo(4);
        assertThat(result.aiExtracted()).isFalse();
        assertThat(result.evidence()).contains("2 of 64");
    }

    @Test
    void noMatch_passed() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", found(0)));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
    }

    @Test
    void shortLetter_notApplicable() {
        RegistryFindings noFingerprint = RegistryFindings.checked(true, false, RegistryMatch.NONE, Set.of());

        CheckResult result = check.evaluate(letter("Pay now.", noFingerprint));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(result.explanation()).contains("50 words");
    }

    @Test
    void registryDown_unavailable() {
        CheckResult result = check.evaluate(letter("Dear Candidate.", RegistryFindings.unavailable(true, true)));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.UNAVAILABLE);
    }

    @Test
    void knownCompanyListDown_throwsSoTheAnalysisMarksItUnavailable() {
        com.offershield.company.KnownCompanies broken = mock(com.offershield.company.KnownCompanies.class);
        when(broken.directory()).thenThrow(new IllegalStateException("database down"));
        NearDuplicateCheck failing = new NearDuplicateCheck(new CopiedLetterGuard(broken, new FreeMailDomains()));

        assertThatThrownBy(() -> failing.evaluate(Letters.from("hr@northwind-analytics.com", "Dear Candidate.")
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 0))))).isInstanceOf(IllegalStateException.class);
    }
}
