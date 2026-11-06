package com.offershield.analysis.scoring;

import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckOutcome;
import com.offershield.analysis.checks.CheckResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PRD §7.2 / §18: summed points capped at 100, bands 0–24 / 25–49 / ≥ 50, hard overrides only from C1, C10, C11. */
class ScoringEngineTest {

    private final ScoringEngine engine = new ScoringEngine();

    @Test
    void moneyRequest_high() {
        Score score = engine.score(List.of(
                CheckResult.override(CheckCode.C1, "Asks for money.", "Pay ₹5,000 deposit", 1),
                CheckResult.passed(CheckCode.C2, "Not a free-mail sender.", 1)));

        assertThat(score.band()).isEqualTo(Band.HIGH);
        assertThat(score.hardOverride()).isTrue();
        assertThat(score.points()).isZero();
        assertThat(score.band().message()).isEqualTo("Strong scam indicators. Do not pay anything.");
    }

    @Test
    void onlyC1C10C11_canOverride() {
        for (CheckCode code : CheckCode.values()) {
            CheckResult forced = new CheckResult(code, CheckOutcome.TRIGGERED, 0, true, "Forced.", null, 1, false);

            Score score = engine.score(List.of(forced));

            boolean allowed = code == CheckCode.C1 || code == CheckCode.C10 || code == CheckCode.C11;
            assertThat(score.hardOverride()).as(code.name()).isEqualTo(allowed);
            assertThat(score.band()).as(code.name()).isEqualTo(allowed ? Band.HIGH : Band.LOW);
        }
    }

    @Test
    void unverifiedOnly_neverOverrides() {
        Score score = engine.score(List.of(
                new CheckResult(CheckCode.C12, CheckOutcome.TRIGGERED, 25, true, "Reported by 3 users (not verified).", null, 5, false)));

        assertThat(score.hardOverride()).isFalse();
        assertThat(score.points()).isEqualTo(25);
        assertThat(score.band()).isEqualTo(Band.MEDIUM);
    }

    @Test
    void c11FromOfficialSender_addsPointsWithoutOverride() {
        Score score = engine.score(List.of(
                CheckResult.triggered(CheckCode.C11, 20, "Wording resembles a reported scam.", null, 4)));

        assertThat(score.hardOverride()).isFalse();
        assertThat(score.points()).isEqualTo(20);
        assertThat(score.band()).isEqualTo(Band.LOW);
    }

    @ParameterizedTest
    @CsvSource({"0,LOW", "24,LOW", "25,MEDIUM", "49,MEDIUM", "50,HIGH", "100,HIGH"})
    void bandBoundaries(int points, Band expected) {
        Score score = engine.score(points == 0 ? List.of() : List.of(
                CheckResult.triggered(CheckCode.C4, points, "Points.", null, 3)));

        assertThat(score.points()).isEqualTo(points);
        assertThat(score.band()).isEqualTo(expected);
        assertThat(score.hardOverride()).isFalse();
    }

    @Test
    void cappedAt100() {
        Score score = engine.score(List.of(
                CheckResult.triggered(CheckCode.C4, 30, "Lookalike.", null, 3),
                CheckResult.triggered(CheckCode.C2, 25, "Free mail.", null, 1),
                CheckResult.triggered(CheckCode.C3, 20, "Mismatch.", null, 3),
                CheckResult.triggered(CheckCode.C12, 25, "Reported.", null, 5),
                CheckResult.triggered(CheckCode.C5, 20, "Young.", null, 2),
                CheckResult.triggered(CheckCode.C8, 15, "WhatsApp only.", null, 1)));

        assertThat(score.points()).isEqualTo(100);
        assertThat(score.band()).isEqualTo(Band.HIGH);
    }

    @Test
    void untriggeredResults_addNothing() {
        Score score = engine.score(List.of(
                CheckResult.passed(CheckCode.C1, "No money request.", 1),
                CheckResult.unavailable(CheckCode.C5, "No data.", 2),
                CheckResult.notApplicable(CheckCode.C6, "No domain.", 2)));

        assertThat(score.points()).isZero();
        assertThat(score.band()).isEqualTo(Band.LOW);
    }

    @Test
    void bandMessages_verbatimFromPrd() {
        assertThat(Band.LOW.message()).isEqualTo("No major red flags found. This does not prove the offer is genuine.");
        assertThat(Band.MEDIUM.message())
                .isEqualTo("Warning signs. Verify through the company's official website or careers page.");
        assertThat(Band.HIGH.message()).isEqualTo("Strong scam indicators. Do not pay anything.");
    }
}
