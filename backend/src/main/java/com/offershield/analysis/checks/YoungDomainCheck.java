package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.RdapClient;
import com.offershield.analysis.lookup.RdapLookup;
import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.FreeMailDomains;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * C5, young domain (PRD §7.1, §7.2): the sender domain's RDAP registration date. Under 90 days +20, under a year +10.
 * No registration date (some {@code .in} records, I-29), a date in the future or a failed lookup → UNAVAILABLE, never
 * "safe". Runs only for a {@link ValidatedDomain} (SSRF guard) that is not a free-mail provider. Source kind 2.
 */
@Component
public class YoungDomainCheck implements RiskCheck {

    static final int VERY_YOUNG_DAYS = 90;
    static final int YOUNG_DAYS = 365;
    static final int VERY_YOUNG_POINTS = 20;
    static final int YOUNG_POINTS = 10;

    private final RdapClient rdap;
    private final FreeMailDomains freeMail;
    private final Clock clock;

    @Autowired
    public YoungDomainCheck(RdapClient rdap, FreeMailDomains freeMail) {
        this(rdap, freeMail, Clock.systemUTC());
    }

    YoungDomainCheck(RdapClient rdap, FreeMailDomains freeMail, Clock clock) {
        this.rdap = rdap;
        this.freeMail = freeMail;
        this.clock = clock;
    }

    @Override
    public CheckCode code() {
        return CheckCode.C5;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        ValidatedDomain domain = letter.domain();
        if (domain == null) {
            return CheckResult.notApplicable(CheckCode.C5,
                    "There is no public sender domain to look up, so the domain's age wasn't checked.", 2);
        }
        if (freeMail.contains(domain.registrableDomain())) {
            return CheckResult.notApplicable(CheckCode.C5,
                    "The sender uses a free email service, so the age of that service's domain says nothing about the employer.", 2);
        }
        RdapLookup lookup = rdap.lookup(domain);
        Instant now = clock.instant();
        if (lookup.status() != RdapLookup.Status.FOUND || lookup.registeredAt().isAfter(now.plus(Duration.ofDays(1)))) {
            return CheckResult.unavailable(CheckCode.C5,
                    "The domain registry gave no usable registration date for " + domain.registryDomain()
                            + ", so the domain's age is unknown.", 2);
        }
        long days = Math.max(0, Duration.between(lookup.registeredAt(), now).toDays());
        String evidence = Evidence.snippet(domain.registryDomain() + " registered on "
                + LocalDate.ofInstant(lookup.registeredAt(), ZoneOffset.UTC) + " (" + days + " days ago), per its registry (RDAP)");
        if (days < VERY_YOUNG_DAYS) {
            return CheckResult.triggered(CheckCode.C5, VERY_YOUNG_POINTS,
                    "The sender's domain was registered less than 90 days ago. Scammers often use brand-new domains.",
                    evidence, 2);
        }
        if (days < YOUNG_DAYS) {
            return CheckResult.triggered(CheckCode.C5, YOUNG_POINTS,
                    "The sender's domain is less than a year old.", evidence, 2);
        }
        return CheckResult.passed(CheckCode.C5,
                "The sender's domain was registered more than a year ago. An older domain alone doesn't prove the offer is genuine.", 2);
    }
}
