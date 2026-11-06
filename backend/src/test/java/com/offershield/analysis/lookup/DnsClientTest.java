package com.offershield.analysis.lookup;

import com.offershield.support.FakeDnsResolver;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Rcode;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P6-05: MX and DMARC via dnsjava against an in-memory resolver; 3 s budget, failures UNAVAILABLE, 1 h cache. */
class DnsClientTest {

    private final DomainValidator validator = new DomainValidator();
    private final FakeDnsResolver dns = new FakeDnsResolver();

    @Test
    void mxAndDmarc_found() {
        dns.mx("acme-careers.com", "mx1.mail-host.com").txt("_dmarc.acme-careers.com", "v=DMARC1; p=reject");

        MailRecords records = client(Duration.ofSeconds(3)).mailRecords(domain("acme-careers.com"));

        assertThat(records).isEqualTo(MailRecords.found(true, true));
        assertThat(dns.queries()).containsExactly("acme-careers.com MX", "_dmarc.acme-careers.com TXT");
    }

    @Test
    void nxdomain_noMxNoDmarc() {
        assertThat(client(Duration.ofSeconds(3)).mailRecords(domain("brand-new-firm.com")))
                .isEqualTo(MailRecords.found(false, false));
    }

    @Test
    void nullMx_countsAsNoMx() {
        dns.mx("no-mail.com", ".").txt("_dmarc.no-mail.com", "v=DMARC1; p=none");

        assertThat(client(Duration.ofSeconds(3)).mailRecords(domain("no-mail.com"))).isEqualTo(MailRecords.found(false, true));
    }

    @Test
    void txtWithoutDmarcTag_notCounted() {
        dns.mx("spf-only.com", "mx.spf-only.com").txt("_dmarc.spf-only.com", "v=spf1 -all");

        assertThat(client(Duration.ofSeconds(3)).mailRecords(domain("spf-only.com"))).isEqualTo(MailRecords.found(true, false));
    }

    @Test
    void subdomainSender_dmarcFromOrganisationalDomain() {
        dns.mx("hr.big-employer.co.in", "mx.big-employer.co.in").txt("_dmarc.big-employer.co.in", "v=DMARC1; p=quarantine");

        MailRecords records = client(Duration.ofSeconds(3)).mailRecords(domain("hr.big-employer.co.in"));

        assertThat(records).isEqualTo(MailRecords.found(true, true));
        assertThat(dns.queries()).containsExactly("hr.big-employer.co.in MX", "_dmarc.hr.big-employer.co.in TXT",
                "_dmarc.big-employer.co.in TXT");
    }

    @Test
    void servfail_unavailable() {
        dns.fail("flaky-dns.com", Rcode.SERVFAIL);

        assertThat(client(Duration.ofSeconds(3)).mailRecords(domain("flaky-dns.com")).available()).isFalse();
    }

    @Test
    void timeout_unavailable() {
        dns.hang("silent-dns.com");

        long start = System.nanoTime();
        MailRecords records = client(Duration.ofMillis(300)).mailRecords(domain("silent-dns.com"));

        assertThat(records).isEqualTo(MailRecords.unavailable());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void cached_noSecondQuery() {
        dns.mx("cached-firm.com", "mx.cached-firm.com");
        DnsClient client = client(Duration.ofSeconds(3));

        client.mailRecords(domain("cached-firm.com"));
        client.mailRecords(domain("cached-firm.com"));

        assertThat(dns.queries()).containsExactly("cached-firm.com MX", "_dmarc.cached-firm.com TXT");
    }

    @Test
    void failure_notCached() {
        dns.fail("flaky-dns.com", Rcode.SERVFAIL);
        DnsClient client = client(Duration.ofSeconds(3));

        client.mailRecords(domain("flaky-dns.com"));
        client.mailRecords(domain("flaky-dns.com"));

        assertThat(dns.queries()).containsExactly("flaky-dns.com MX", "flaky-dns.com MX");
    }

    private DnsClient client(Duration timeout) {
        return new DnsClient(dns, new LookupProperties(
                new LookupProperties.Rdap(null, null, true, 0, null),
                new LookupProperties.Dns(timeout, Duration.ofHours(1), List.of())));
    }

    private ValidatedDomain domain(String name) {
        return validator.validate(name).orElseThrow();
    }
}
