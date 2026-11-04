package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.DnsClient;
import com.offershield.analysis.lookup.MailRecords;
import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.FreeMailDomains;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * C6, mail setup (PRD §7.1, §7.2): no MX record +15; no DMARC record +5 (low, because many genuine small firms lack
 * it). A failed DNS lookup → UNAVAILABLE. Runs only for a {@link ValidatedDomain} (SSRF guard) that is not a free-mail
 * provider. Source kind 2.
 */
@Component
public class MailSetupCheck implements RiskCheck {

    static final int NO_MX_POINTS = 15;
    static final int NO_DMARC_POINTS = 5;

    private final DnsClient dns;
    private final FreeMailDomains freeMail;

    public MailSetupCheck(DnsClient dns, FreeMailDomains freeMail) {
        this.dns = dns;
        this.freeMail = freeMail;
    }

    @Override
    public CheckCode code() {
        return CheckCode.C6;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        ValidatedDomain domain = letter.domain();
        if (domain == null) {
            return CheckResult.notApplicable(CheckCode.C6,
                    "There is no public sender domain to look up, so its mail setup wasn't checked.", 2);
        }
        if (freeMail.contains(domain.registrableDomain())) {
            return CheckResult.notApplicable(CheckCode.C6,
                    "The sender uses a free email service, whose mail setup says nothing about the employer.", 2);
        }
        MailRecords records = dns.mailRecords(domain);
        if (!records.available()) {
            return CheckResult.unavailable(CheckCode.C6,
                    "The DNS lookup for the sender's domain didn't answer in time or returned an error, so its mail "
                            + "setup is unknown.", 2);
        }
        int points = 0;
        List<String> missing = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        if (!records.hasMx()) {
            points += NO_MX_POINTS;
            missing.add("no mail server (MX record)");
            evidence.add("No MX record for " + domain.host());
        }
        if (!records.hasDmarc()) {
            points += NO_DMARC_POINTS;
            missing.add("no DMARC record");
            evidence.add("No DMARC record for " + domain.host());
        }
        if (points == 0) {
            return CheckResult.passed(CheckCode.C6, "The sender's domain has a mail server (MX) and a DMARC record.", 2);
        }
        return CheckResult.triggered(CheckCode.C6, points,
                "The sender's domain has " + String.join(" and ", missing)
                        + ". Companies that email offers usually set these up.",
                Evidence.snippet(String.join("; ", evidence)), 2);
    }
}
