package com.offershield.analysis.checks;

import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.Score;
import com.offershield.analysis.scoring.ScoringEngine;
import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import com.offershield.registry.IndicatorStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.offershield.analysis.checks.RegistryFixtures.found;
import static com.offershield.analysis.checks.RegistryFixtures.wording;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §7.3 / §18 copied-letter guard (P9-04, R-A; review focus #3). Scammers copy a real employer's letter word for
 * word; once that copy is confirmed, a later genuine letter from the same company matches its SimHash. A C11 match
 * forces High only when the sender is not on an official domain of a company the rules identified in the letter (its
 * known-company name in the text); then it is +20 with "wording resembles a reported scam". The AI's company name alone
 * can never earn that downgrade. Fictional company prose; the official domain is a made-up {@code .com} string.
 */
class CopiedLetterGuardTest {

    private static final String LETTER = "Northwind Analytics Private Limited is pleased to offer you the role of "
            + "Graduate Engineer Trainee. We look forward to working with you.";

    private final KnownCompanies known = KnownCompanyFixtures.knownCompanies(
            KnownCompanyFixtures.company("Northwind Analytics", "northwind-analytics.com"));
    private final NearDuplicateCheck check = new NearDuplicateCheck(new CopiedLetterGuard(known, new FreeMailDomains()));

    @Test
    void officialSender_plus20NotHigh() {
        Letter letter = Letters.from("talent@careers.northwind-analytics.com", LETTER)
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 1)));

        CheckResult result = check.evaluate(letter);
        Score score = new ScoringEngine().score(List.of(result));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.hardOverride()).isFalse();
        assertThat(result.points()).isEqualTo(20);
        assertThat(result.explanation()).containsIgnoringCase("wording resembles a reported scam")
                .contains("Northwind Analytics");
        assertThat(score.band()).isNotEqualTo(Band.HIGH);
        assertThat(score.hardOverride()).isFalse();
        assertThat(score.points()).isEqualTo(20);
    }

    @Test
    void nonOfficialSender_high() {
        Letter letter = Letters.from("hr@northwind-analytics-careers.com", LETTER)
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 1)));

        CheckResult result = check.evaluate(letter);

        assertThat(result.hardOverride()).isTrue();
        assertThat(new ScoringEngine().score(List.of(result)).band()).isEqualTo(Band.HIGH);
    }

    @Test
    void aiOnlyCompany_noDowngrade() {
        // The letter text names no known company; only the AI says it is from Northwind Analytics, and the sender is on
        // Northwind's official domain. R-A: the AI cannot be what lowers High to +20.
        Letter letter = Letters.from("talent@northwind-analytics.com", "Dear Candidate, we are pleased to offer you "
                        + "the role of Graduate Engineer Trainee. We look forward to working with you.")
                .withAi(new AiFindings(List.of(), "Northwind Analytics Private Limited", List.of(), List.of()))
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 0)));

        CheckResult result = check.evaluate(letter);

        assertThat(result.hardOverride()).isTrue();
        assertThat(new ScoringEngine().score(List.of(result)).band()).isEqualTo(Band.HIGH);
    }

    @Test
    void freeMailSender_high() {
        Letter letter = Letters.from("northwind.hr@gmail.com", LETTER)
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 0)));

        assertThat(check.evaluate(letter).hardOverride()).isTrue();
    }

    @Test
    void officialDomainOfACompanyTheLetterDoesNotName_high() {
        KnownCompanies two = KnownCompanyFixtures.knownCompanies(
                KnownCompanyFixtures.company("Northwind Analytics", "northwind-analytics.com"),
                KnownCompanyFixtures.company("Zenvora Systems", "zenvora-systems.com"));
        NearDuplicateCheck guarded = new NearDuplicateCheck(new CopiedLetterGuard(two, new FreeMailDomains()));
        Letter letter = Letters.from("hr@zenvora-systems.com", LETTER)
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 0)));

        assertThat(guarded.evaluate(letter).hardOverride()).isTrue();
    }

    @Test
    void guard_identifiesCompanyOnlyFromTheText() {
        CopiedLetterGuard guard = new CopiedLetterGuard(known, new FreeMailDomains());

        assertThat(guard.officialSender(Letters.from("talent@northwind-analytics.com", LETTER)))
                .hasValueSatisfying(company -> assertThat(company.name()).isEqualTo("Northwind Analytics"));
        assertThat(guard.officialSender(Letters.from("talent@northwind-analytics.com", "Dear Candidate, welcome.")
                .withAi(new AiFindings(List.of(), "Northwind Analytics", List.of(), List.of())))).isEmpty();
        assertThat(guard.officialSender(Letters.text(LETTER))).isEmpty();
    }

    // --- P13 (security review S2): a sender address taken from the letter itself (no typed sender email) ---

    @Test
    void letterSourcedSender_otherEmailDomainInLetter_high() {
        // The author planted the official address first, so D-33.6 picked it; the reply really goes to Gmail.
        Letter letter = Letters.fromLetter(LETTER + " Write to talent@northwind-analytics.com, or send the documents "
                        + "to northwind.joining@gmail.com.")
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 1)));

        CheckResult result = check.evaluate(letter);

        assertThat(letter.senderFromLetter()).isTrue();
        assertThat(result.hardOverride()).isTrue();
        assertThat(new ScoringEngine().score(List.of(result)).band()).isEqualTo(Band.HIGH);
    }

    @Test
    void letterSourcedSender_messengerContact_high() {
        Letter letter = Letters.fromLetter(LETTER + " Write to talent@northwind-analytics.com. For joining, message "
                        + "our coordinator on WhatsApp.")
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 1)));

        assertThat(check.evaluate(letter).hardOverride()).isTrue();
    }

    @Test
    void letterSourcedSender_onlyOfficialAddresses_plus20() {
        Letter letter = Letters.fromLetter(LETTER + " Write to talent@northwind-analytics.com or to "
                        + "joining@careers.northwind-analytics.com.")
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 1)));

        CheckResult result = check.evaluate(letter);

        assertThat(result.hardOverride()).isFalse();
        assertThat(result.points()).isEqualTo(20);
    }

    @Test
    void typedSender_otherEmailDomainInLetter_stillPlus20() {
        // The student typed the official address they received the letter from: unchanged (D-33.6, S2 is LETTER only).
        Letter letter = Letters.from("talent@northwind-analytics.com", LETTER + " Or write to "
                        + "northwind.joining@gmail.com.")
                .withRegistry(found(0, wording(IndicatorStatus.CONFIRMED, 1)));

        assertThat(check.evaluate(letter).hardOverride()).isFalse();
    }
}
