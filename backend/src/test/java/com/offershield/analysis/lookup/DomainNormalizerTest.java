package com.offershield.analysis.lookup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.IDN;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-03 / PRD F8 "IDN/punycode homoglyph handling": the look-alike skeleton maps characters that look the same to one
 * letter (0→o; 1, i and l → l; rn→m; vv→w; 3→e, 4→a, 5→s, 7→t, 8→b; Cyrillic and Greek look-alikes; accents and
 * full-width forms), so a brand and its imitation end up with the same skeleton.
 */
class DomainNormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {"infosys", "INFOSYS", "inf0sys", "lnfosys", "1nfosys", "Inf0sys", "ínfosys", "ｉｎｆｏｓｙｓ",
            "іnfosys" /* Cyrillic i */, "infоsys" /* Cyrillic o */, "infοsys" /* Greek omicron */,
            "infosyѕ" /* Cyrillic dze */})
    void lookAlikes_sameSkeletonAsBrand(String spelling) {
        assertThat(DomainNormalizer.skeleton(spelling)).isEqualTo(DomainNormalizer.skeleton("infosys")).isEqualTo("lnfosys");
    }

    @ParameterizedTest
    @CsvSource({
            "rnicrosoft, mlcrosoft",
            "vvipro, wlpro",
            "w1pr0, wlpro",
            "acc3ntur3, accenture",
            "4ccenture, accenture",
            "5ap-labs, sap-labs",
            "7ech, tech",
            "8ank, bank",
            "inf0sys-careers, lnfosys-careers"})
    void digitsAndLetterPairs_mapped(String input, String expected) {
        assertThat(DomainNormalizer.skeleton(input)).isEqualTo(expected);
    }

    @Test
    void punycodeLabel_decodedBeforeMapping() {
        String punycode = IDN.toASCII("іnfosys"); // Cyrillic і + "nfosys"
        assertThat(punycode).startsWith("xn--");

        assertThat(DomainNormalizer.skeleton(punycode)).isEqualTo("lnfosys");
    }

    @Test
    void nullOrEmpty_empty() {
        assertThat(DomainNormalizer.skeleton(null)).isEmpty();
        assertThat(DomainNormalizer.skeleton("")).isEmpty();
    }

    @Test
    void labelsBeforeSuffix_subdomainsKeptSuffixDropped() {
        DomainValidator validator = new DomainValidator();

        assertThat(DomainNormalizer.labelsBeforeSuffix(validator.validate("hr.inf0sys-careers.co.in").orElseThrow()))
                .containsExactly("hr", "inf0sys-careers");
        assertThat(DomainNormalizer.labelsBeforeSuffix(validator.validate("infosys.com").orElseThrow()))
                .containsExactly("infosys");
        assertThat(DomainNormalizer.labelsBeforeSuffix(validator.validate("careers.someone.github.io").orElseThrow()))
                .as("a private suffix (github.io) counts as the suffix, like the registrable domain (M-06)")
                .containsExactly("careers", "someone");
    }
}
