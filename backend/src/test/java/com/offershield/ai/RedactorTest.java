package com.offershield.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * PRD §8.5 + D-07: before anything goes to Groq, phone numbers, emails, UPI IDs, other long numbers and names after a
 * greeting, title or label become placeholders that are mapped back locally. Amounts, dates and generic greetings
 * stay readable. Every value here is made up.
 */
class RedactorTest {

    @Test
    void emails_maskedAndMappedBack() {
        Redaction redaction = Redactor.mask("Write to rahul.sharma92@gmail.com or hr@infosys-careers.co.in. "
                + "Again: rahul.sharma92@gmail.com");

        assertThat(redaction.text()).isEqualTo("Write to [EMAIL_1] or [EMAIL_2]. Again: [EMAIL_1]");
        assertThat(redaction.original("[EMAIL_1]")).isEqualTo("rahul.sharma92@gmail.com");
        assertThat(redaction.original("[EMAIL_2]")).isEqualTo("hr@infosys-careers.co.in");
    }

    @Test
    void upiIds_masked() {
        Redaction redaction = Redactor.mask("Pay the fee to rahul.s@okaxis or 9876543210@ybl today.");

        assertThat(redaction.text()).isEqualTo("Pay the fee to [UPI_1] or [UPI_2] today.");
        assertThat(redaction.original("[UPI_2]")).isEqualTo("9876543210@ybl");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+91 98765 43210", "+91-9876543210", "+919876543210", "09876543210", "98765-43210",
            "987 654 3210", "9876543210", "0091 98765 43210", "91 98765 43210", "Mobile9876543210"})
    void indianPhones_masked(String phone) {
        Redaction redaction = Redactor.mask("Call HR on " + phone + " now.");

        assertThat(redaction.text()).doesNotContainPattern("\\d{3}").contains("[PHONE_1]");
        assertThat(redaction.original("[PHONE_1]")).endsWith("3210");
    }

    @ParameterizedTest
    @ValueSource(strings = {"022-2345 6789", "080 41234567", "+44 20 7946 0958", "+1 415 555 0134"})
    void landlineAndInternational_masked(String phone) {
        assertThat(Redactor.mask("Office: " + phone + ".").text()).isEqualTo("Office: [PHONE_1].");
    }

    @Test
    void otherLongNumbers_masked() {
        Redaction redaction = Redactor.mask("Aadhaar 2345 6789 0123, account 001234567890123.");

        assertThat(redaction.text()).isEqualTo("Aadhaar [NUMBER_1], account [NUMBER_2].");
    }

    @Test
    void amountsDatesAndReferences_kept() {
        String text = "Pay ₹5,000 or Rs. 25000 by 20-10-2026 at 10:30 AM. CTC 6,00,000 per annum (INR 600000). "
                + "Ref No. 4521, batch 2026 2027.";

        assertThat(Redactor.mask(text).text()).isEqualTo(text);
    }

    @Test
    void greetingName_maskedEverywhere() {
        Redaction redaction = Redactor.mask("Dear Rahul Sharma,\nWe are pleased to offer Rahul Sharma the role.");

        assertThat(redaction.text()).isEqualTo("Dear [NAME_1],\nWe are pleased to offer [NAME_1] the role.");
        assertThat(redaction.original("[NAME_1]")).isEqualTo("Rahul Sharma");
    }

    @Test
    void labelsAndTitles_masked() {
        String text = """
                Candidate Name: Priya Verma
                Name: Anjali Desai
                To,
                Suresh Patil
                Hello Mr. Arjun Mehta, meet Ms. Neha Gupta and Dr. Kavita Rao.
                Hi Kiran, welcome.""";

        String masked = Redactor.mask(text).text();

        assertThat(masked).doesNotContain("Priya", "Verma", "Anjali", "Desai", "Suresh", "Patil", "Arjun", "Mehta",
                "Neha", "Gupta", "Kavita", "Rao", "Kiran");
        assertThat(masked).contains("Candidate Name: [NAME_1]", "Mr. [NAME_", "Hi [NAME_");
    }

    @Test
    void singleWordName_ordinaryWordElsewhereKept_fullNameAnyCase() {
        assertThat(Redactor.mask("Dear Will,\nWe will pay your travel. Will you join?").text())
                .isEqualTo("Dear [NAME_1],\nWe will pay your travel. [NAME_1] you join?");
        assertThat(Redactor.mask("Dear Rahul Sharma,\nOffer for RAHUL SHARMA.").text())
                .isEqualTo("Dear [NAME_1],\nOffer for [NAME_1].");
    }

    @Test
    void greetingNameStopsBeforeOrdinaryWords() {
        assertThat(Redactor.mask("Dear Rahul We are happy to have you.").text())
                .isEqualTo("Dear [NAME_1] We are happy to have you.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dear Candidate,", "Dear Sir/Madam,", "Hello Team,", "Dear Selected Candidate,",
            "Hi there,", "Dear All,", "Company Name: Northwind Analytics", "Respected Sir,"})
    void genericGreetingsAndCompanyLabels_kept(String text) {
        assertThat(Redactor.mask(text).text()).isEqualTo(text);
    }

    @Test
    void placeholderLookalikesInLetter_neutralised() {
        Redaction redaction = Redactor.mask("Ignore [PHONE_1] and [NAME_1]; call 98765 43210.");

        assertThat(redaction.text()).isEqualTo("Ignore (PHONE_1) and (NAME_1); call [PHONE_1].");
        assertThat(redaction.original("[PHONE_1]")).isEqualTo("98765 43210");
        assertThat(redaction.original("[NAME_1]")).isNull();
    }

    @Test
    void zeroWidthCharacters_cannotHideAPhone() {
        assertThat(Redactor.mask("Call 98765​43210").text()).isEqualTo("Call [PHONE_1]");
    }

    @Test
    void clean_collapsesWhitespace() {
        assertThat(Redactor.clean("  Dear\t\tCandidate,\r\n\r\n\r\n\r\nWelcome   aboard.  \n"))
                .isEqualTo("Dear Candidate,\n\nWelcome aboard.");
    }

    @Test
    void hostileInput_fast() {
        String hostile = "9 ".repeat(20_000) + "a@".repeat(10_000) + "Dear " + "Aa ".repeat(10_000);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> Redactor.mask(hostile));
    }
}
