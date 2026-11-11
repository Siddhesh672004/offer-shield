package com.offershield.registry;

import com.offershield.analysis.lookup.DomainValidator;
import com.offershield.common.web.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The registry's status of one sender domain (PRD §11), for any signed-in user. The input must pass the SSRF guard's
 * syntax rules ({@link DomainValidator}; nothing is fetched) and is looked up as its registrable domain. Free-mail and
 * known companies' official domains are "not tracked" (D-08). Only a CONFIRMED marker is shown as "Confirmed scam"; an
 * UNVERIFIED one is "Reported as a scam by N users (not verified)" (CLAUDE.md copy rules); REJECTED counts as none.
 */
@Service
public class DomainStatusService {

    private final DomainValidator validator;
    private final SharedDomains sharedDomains;
    private final IndicatorRepository indicators;

    public DomainStatusService(DomainValidator validator, SharedDomains sharedDomains, IndicatorRepository indicators) {
        this.validator = validator;
        this.sharedDomains = sharedDomains;
        this.indicators = indicators;
    }

    @Transactional(readOnly = true)
    public DomainStatusResponse lookup(String rawDomain) {
        String domain = validator.validate(rawDomain)
                .orElseThrow(() -> new BadRequestException("INVALID_DOMAIN",
                        "Enter a domain name such as example.com, without an address, port or path."))
                .registrableDomain();
        if (sharedDomains.isShared(domain)) {
            return new DomainStatusResponse(domain, DomainStatus.NOT_TRACKED, null, "This is a shared email domain "
                    + "(a free email provider or a listed company's official domain), so OfferShield never records it "
                    + "as a scam marker. Check the letter itself.");
        }
        return indicators.findByKey(IndicatorType.DOMAIN, domain)
                .filter(row -> row.status() != IndicatorStatus.REJECTED)
                .map(row -> row.status() == IndicatorStatus.CONFIRMED
                        ? new DomainStatusResponse(domain, DomainStatus.CONFIRMED, null,
                        "Confirmed scam: a TPO or the admin confirmed a scam sent from this domain.")
                        : new DomainStatusResponse(domain, DomainStatus.UNVERIFIED, row.reporterCount(),
                        reportedBy(row.reporterCount())))
                .orElseGet(() -> new DomainStatusResponse(domain, DomainStatus.NONE, null,
                        "No scam reports for this domain. This does not prove that an offer from it is genuine."));
    }

    /** CLAUDE.md copy rule, verbatim: "Reported as a scam by N users (not verified)". */
    static String reportedBy(int people) {
        return "Reported as a scam by " + people + (people == 1 ? " user" : " users") + " (not verified)";
    }
}
