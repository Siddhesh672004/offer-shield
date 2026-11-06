package com.offershield.analysis;

import com.offershield.analysis.checks.Letters;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §10.1 / §18 (P9-02, D-18): the SimHash fingerprint. A copy of a letter with only names, numbers, dates and
 * contact details changed has the same fingerprint (distance 0–1); a near-duplicate is within 3 bits; different
 * letters, even two genuine letters sharing standard sentences, are more than 3 bits apart; texts under 50 words get
 * none. Fake letters only (05 §4).
 */
class SimHashTest {

    private static final int NEAR = 3;

    private final String genuine = Letters.fixture(Letters.GENUINE);

    @Test
    void namesNumbersDatesChanged_zeroToOne() {
        String a = genuine.replace("Dear Candidate,", "Dear Ananya Rao,");
        String b = Letters.fixture(Letters.COPIED_GENUINE).replace("Dear Candidate,", "Dear Vikram Shah,")
                + "\nCall +91 99999 01999 if the link does not open.\n";
        String aWithPhone = a + "\nCall +91 99999 01234 if the link does not open.\n";

        assertThat(distance(aWithPhone, b)).isBetween(0, 1);
    }

    @Test
    void nearDuplicate_withinThree() {
        // A re-typed copy: other capitals, line breaks, spacing and punctuation, other amounts, dates and contact
        // address, and one word added (measured: 2 bits). A whole added sentence, such as a new payment line, moves a
        // 126-word letter 4-6 bits, beyond the PRD's threshold of 3; repeated payment details are C10's exact matches.
        String copy = Letters.fixture(Letters.COPIED_GENUINE)
                .replace("We look forward to working with you.", "We look forward to working with you all.")
                .replace("Congratulations!", "Congratulations.").replace(", we are", " we are")
                .toUpperCase().replace("\n", "  \n\n");

        int distance = distance(genuine, copy);

        assertThat(distance).isLessThanOrEqualTo(NEAR);
    }

    @Test
    void differentTexts_beyondThree() {
        assertThat(distance(genuine, Letters.fixture(Letters.NO_FEE_GENUINE))).isGreaterThan(NEAR);
        assertThat(distance(genuine, Letters.fixture(Letters.DEPOSIT_SCAM))).isGreaterThan(NEAR);
        assertThat(distance(Letters.fixture(Letters.DEPOSIT_SCAM), Letters.fixture(Letters.NO_FEE_GENUINE)))
                .isGreaterThan(NEAR);
    }

    @Test
    void genuineLettersSharedBoilerplate_beyondThree() {
        // Same greeting, acceptance paragraph, contact sentence and sign-off; different employer, role and terms.
        String other = Letters.fixture(Letters.OTHER_COMPANY_GENUINE);

        assertThat(distance(genuine, other)).isGreaterThan(NEAR);
    }

    @Test
    void moreGenuinePairs_beyondThree() {
        // P13 (test gap): genuine letters with the same company header, greeting and sign-off, or the same standard
        // sentences, are different letters, not near-duplicates of each other.
        String fraudWarning = Letters.fixture("genuine-with-fraud-warning.txt");
        String other = Letters.fixture(Letters.OTHER_COMPANY_GENUINE);
        String noFee = Letters.fixture(Letters.NO_FEE_GENUINE);

        assertThat(distance(genuine, fraudWarning)).as("same company, different offer").isGreaterThan(NEAR);
        assertThat(distance(fraudWarning, other)).isGreaterThan(NEAR);
        assertThat(distance(noFee, other)).isGreaterThan(NEAR);
    }

    @Test
    void under50Words_none() {
        String fortyNine = IntStream.range(0, 49).mapToObj(i -> "word" + (char) ('a' + i % 26))
                .collect(Collectors.joining(" "));
        String fifty = fortyNine + " last";

        assertThat(SimHasher.fingerprint("Dear Candidate, pay Rs. 5,000 today to join.")).isEmpty();
        assertThat(SimHasher.fingerprint(fortyNine)).isEmpty();
        assertThat(SimHasher.fingerprint(fifty)).isPresent();
        assertThat(SimHasher.fingerprint("")).isEmpty();
        assertThat(SimHasher.fingerprint(null)).isEmpty();
    }

    @Test
    void sameText_sameFingerprintEveryTime() {
        assertThat(SimHasher.fingerprint(genuine)).isEqualTo(SimHasher.fingerprint(genuine));
    }

    @Test
    void words_cleanedAsPrdSaysStep1() {
        assertThat(SimHasher.words("Dear Rahul Sharma, pay Rs. 5,000 on 15th March to test.pay@upi or call "
                + "+91 99999 01234 or mail hr@example.com on Monday."))
                .containsExactly("dear", "[name]", "pay", "rs", "#", "on", "#", "[month]", "to", "[upi]", "or",
                        "call", "[phone]", "or", "mail", "[email]", "on", "[day]");
    }

    private static int distance(String a, String b) {
        OptionalLong first = SimHasher.fingerprint(a);
        OptionalLong second = SimHasher.fingerprint(b);
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        return SimHasher.distance(first.getAsLong(), second.getAsLong());
    }
}
