package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.DnsClient;
import com.offershield.analysis.lookup.MailRecords;
import com.offershield.common.FreeMailDomains;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** C6 (PRD §7.1, §7.2): no MX +15, no DMARC +5; a failed lookup → UNAVAILABLE. Source kind 2 (DNS). */
class MailSetupCheckTest {

    private final DnsClient dns = mock(DnsClient.class);
    private final MailSetupCheck check = new MailSetupCheck(dns, new FreeMailDomains());

    @Test
    void noMx_15() {
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(false, true));

        CheckResult result = check.evaluate(Letters.from("hr@no-mail-firm.com", "Dear Candidate"));

        assertThat(result.code()).isEqualTo(CheckCode.C6);
        assertThat(result.outcome()).isEqualTo(CheckOutcome.TRIGGERED);
        assertThat(result.points()).isEqualTo(15);
        assertThat(result.sourceKind()).isEqualTo(2);
        assertThat(result.evidence()).contains("No MX record for no-mail-firm.com");
    }

    @Test
    void noDmarc_5() {
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, false));

        CheckResult result = check.evaluate(Letters.from("hr@small-firm.com", "Dear Candidate"));

        assertThat(result.points()).isEqualTo(5);
        assertThat(result.evidence()).contains("No DMARC record");
    }

    @Test
    void neither_20() {
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(false, false));

        assertThat(check.evaluate(Letters.from("hr@brand-new-firm.com", "Dear Candidate")).points()).isEqualTo(20);
    }

    @Test
    void both_passed() {
        when(dns.mailRecords(any())).thenReturn(MailRecords.found(true, true));

        CheckResult result = check.evaluate(Letters.from("hr@acme-careers.com", "Dear Candidate"));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(result.points()).isZero();
    }

    @Test
    void timeout_unavailable() {
        when(dns.mailRecords(any())).thenReturn(MailRecords.unavailable());

        CheckResult result = check.evaluate(Letters.from("hr@silent-dns.com", "Dear Candidate"));

        assertThat(result.outcome()).isEqualTo(CheckOutcome.UNAVAILABLE);
        assertThat(result.points()).isZero();
        // P13 (silent failure 18): an unavailable lookup is a timeout or a DNS error (SERVFAIL, REFUSED); say both.
        assertThat(result.explanation()).contains("didn't answer in time or returned an error");
    }

    @Test
    void noValidDomainOrFreeMail_notApplicable_noLookup() {
        assertThat(check.evaluate(Letters.from("x@[::1]", "Dear Candidate")).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        assertThat(check.evaluate(Letters.from("jobs@gmail.com", "Dear Candidate")).outcome()).isEqualTo(CheckOutcome.NOT_APPLICABLE);
        verifyNoInteractions(dns);
    }
}
