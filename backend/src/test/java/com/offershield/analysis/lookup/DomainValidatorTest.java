package com.offershield.analysis.lookup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §14.5 / §18 SSRF guard (never cut): only a syntactically valid public domain name passes, and the registrable
 * domain comes from the public suffix list (M-06: Guava {@code topPrivateDomain()}).
 */
class DomainValidatorTest {

    private final DomainValidator validator = new DomainValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            // PRD §14.5 / §18 inputs
            "127.0.0.1", "[::1]", "169.254.169.254", "evil.com:8080/x", "http://x",
            // other IP literal spellings
            "2130706433", "0x7f.0.0.1", "0177.0.0.1", "127.1", "::1", "[127.0.0.1]", "fe80::1%25eth0", "１２７.0.0.1",
            // local and special-use names
            "localhost", "LOCALHOST", "foo.localhost", "printer.local", "db.internal", "router.home.arpa",
            "1.0.0.127.in-addr.arpa", "hr.example", "jobs.test", "x.invalid", "intranet",
            // schemes, ports, paths, user info, queries, spaces
            "https://evil.com", "evil.com/path", "evil.com:443", "user@evil.com", "evil.com?x=1", "evil.com#f",
            "evil .com", "evil.com\n.attacker.com", "%65vil.com",
            // malformed names
            ".com", "evil..com", "-evil.com", "evil-.com", "evil.c", "co.in", "com", "under_score.com"})
    void unsafeOrInvalid_rejected(String input) {
        assertThat(validator.validate(input)).as(input).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blank_rejected(String input) {
        assertThat(validator.validate(input)).isEmpty();
    }

    @Test
    void overLongNames_rejected() {
        String longLabel = "a".repeat(64) + ".com";
        String longName = ("a".repeat(60) + ".").repeat(5) + "com";

        assertThat(validator.validate(longLabel)).isEmpty();
        assertThat(longName.length()).isGreaterThan(253);
        assertThat(validator.validate(longName)).isEmpty();
    }

    @Test
    void subdomain_registrableDomainFromPublicSuffixList() {
        ValidatedDomain domain = validator.validate("hr.inf0sys-careers.co.in").orElseThrow();

        assertThat(domain.host()).isEqualTo("hr.inf0sys-careers.co.in");
        assertThat(domain.registrableDomain()).isEqualTo("inf0sys-careers.co.in");
        assertThat(domain.registryDomain()).isEqualTo("inf0sys-careers.co.in");
    }

    @Test
    void upperCaseAndTrailingDot_normalised() {
        ValidatedDomain domain = validator.validate(" Careers.Example-Corp.COM. ").orElseThrow();

        assertThat(domain.host()).isEqualTo("careers.example-corp.com");
        assertThat(domain.registrableDomain()).isEqualTo("example-corp.com");
    }

    @Test
    void unicodeName_convertedToAscii() {
        ValidatedDomain domain = validator.validate("bücher.de").orElseThrow();

        assertThat(domain.host()).isEqualTo("xn--bcher-kva.de");
        assertThat(domain.registrableDomain()).isEqualTo("xn--bcher-kva.de");
    }

    @Test
    void privateSuffix_registryDomainIsTheRegisteredName() {
        ValidatedDomain domain = validator.validate("someone.github.io").orElseThrow();

        assertThat(domain.registrableDomain()).isEqualTo("someone.github.io");
        assertThat(domain.registryDomain()).isEqualTo("github.io");
    }

    @Test
    void freeMailDomain_valid() {
        assertThat(validator.validate("gmail.com")).hasValueSatisfying(domain ->
                assertThat(domain.registrableDomain()).isEqualTo("gmail.com"));
    }
}
