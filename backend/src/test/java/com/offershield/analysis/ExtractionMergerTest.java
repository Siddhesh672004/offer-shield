package com.offershield.analysis;

import com.offershield.ai.Extraction;
import com.offershield.analysis.checks.AiFindings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** PRD §7 / R-A: what an AI extraction may add to the analysis (candidates) and show in the report (labelled facts). */
class ExtractionMergerTest {

    private final ExtractionMerger merger = new ExtractionMerger();

    @Test
    void noExtraction_nothingAdded() {
        assertThat(merger.findings(Optional.empty())).isEqualTo(AiFindings.NONE);
        assertThat(merger.details(Optional.empty())).isEqualTo(AiDetails.NONE);
    }

    @Test
    void findings_candidatesPassedOn() {
        AiFindings findings = merger.findings(Optional.of(extraction("Northwind Careers Pvt Ltd", null, null, null)));

        assertThat(findings.paymentMentions()).containsExactly("Pay Rs. 5,000 deposit to [UPI_1]");
        assertThat(findings.companyName()).isEqualTo("Northwind Careers Pvt Ltd");
        assertThat(findings.phoneNumbers()).containsExactly("+91 98765 43210");
        assertThat(findings.upiIds()).containsExactly("northwind.pay@okaxis");
    }

    @Test
    void details_groundedFactsKept() {
        AiDetails details = merger.details(Optional.of(extraction("Northwind  Careers\nPvt Ltd", "Arjun Mehta",
                "6,00,000 per annum", "1 November 2026")));

        assertThat(details).isEqualTo(new AiDetails("Northwind Careers Pvt Ltd", "Arjun Mehta", "6,00,000 per annum",
                "1 November 2026"));
    }

    @Test
    void details_forbiddenWordsPlaceholdersAndLongValuesDropped() {
        AiDetails details = merger.details(Optional.of(extraction("Verified Safe Jobs Ltd", "[NAME_1]",
                "x".repeat(AiDetails.MAX_LENGTH + 1), "Guaranteed joining")));

        assertThat(details.isEmpty()).isTrue();
    }

    private static Extraction extraction(String company, String recruiter, String ctc, String joining) {
        return new Extraction(company, recruiter, ctc, joining, List.of("+91 98765 43210"),
                List.of("northwind.pay@okaxis"), List.of("Pay Rs. 5,000 deposit to [UPI_1]"), "openai/gpt-oss-120b",
                false);
    }
}
