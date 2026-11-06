package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.RdapClient;
import com.offershield.analysis.lookup.RdapLookup;
import com.offershield.common.FreeMailDomains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** C5 (PRD §7.1, §7.2): under 90 days +20, under a year +10; no registration date or a failure → UNAVAILABLE. */
class YoungDomainCheckTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    private final RdapClient rdap = mock(RdapClient.class);
    private final YoungDomainCheck check = new YoungDomainCheck(rdap, new FreeMailDomains(), Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @CsvSource({"0,20", "89,20", "90,10", "364,10", "365,0", "4000,0"})
    void ageBoundaries(int days, int expectedPoints) {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(NOW.minus(Duration.ofDays(days))));

        CheckResult result = check.evaluate(Letters.from("hr@young-firm.com", "Dear Candidate"));

        assertThat(result.code()).isEqualTo(CheckCode.C5);
        assertThat(result.sourceKind()).isEqualTo(2);
        assertThat(result.points()).isEqualTo(expectedPoints);
        assertThat(result.outcome()).isEqualTo(expectedPoints > 0 ? CheckOutcome.TRIGGERED : CheckOutcome.PASSED);
        if (expectedPoints > 0) {
            assertThat(result.evidence()).contains("young-firm.com", days + " days ago");
        }
    }

    @Test
    void missingRegistrationDate_unavailable() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.noRegistrationDate());

        CheckResult result = check.evaluate(Letters.from("hr@nixi-style.in", "Dear Candidate"));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.UNAVAILABLE);
        assertThat(result.points()).isZero();
    }

    @Test
    void lookupFailed_unavailable() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.unavailable());

        assertThat(check.evaluate(Letters.from("hr@young-firm.com", "Dear Candidate")).outcome())
                .isEqualTo(CheckOutcome.UNAVAILABLE);
    }

    @Test
    void registrationInTheFuture_unavailable() {
        when(rdap.lookup(any())).thenReturn(RdapLookup.found(NOW.plus(Duration.ofDays(30))));

        assertThat(check.evaluate(Letters.from("hr@young-firm.com", "Dear Candidate")).outcome())
                .isEqualTo(CheckOutcome.UNAVAILABLE);
    }

    @Test
    void noValidDomain_notApplicable_noLookup() {
        assertThat(check.evaluate(Letters.from("x@127.0.0.1", "Dear Candidate")).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(check.evaluate(Letters.text("Dear Candidate")).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        verifyNoInteractions(rdap);
    }

    @Test
    void freeMailSender_notApplicable_noLookup() {
        assertThat(check.evaluate(Letters.from("jobs.desk@gmail.com", "Dear Candidate")).outcome())
                .isEqualTo(CheckOutcome.NOT_APPLICABLE);
        verifyNoInteractions(rdap);
    }
}
