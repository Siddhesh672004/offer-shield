package com.offershield.registry;

import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import org.springframework.stereotype.Component;

/**
 * The shared-domain guard (D-08): free-mail providers' domains (the fixed public list) and every known company's
 * official domain are used by many genuine senders, so they never become scam markers and are ignored when matching,
 * the same way for everyone. A scam sent from Gmail is still caught by its phone numbers, UPI IDs and wording.
 */
@Component
public class SharedDomains {

    private final FreeMailDomains freeMail;
    private final KnownCompanies knownCompanies;

    public SharedDomains(FreeMailDomains freeMail, KnownCompanies knownCompanies) {
        this.freeMail = freeMail;
        this.knownCompanies = knownCompanies;
    }

    /** @param registrableDomain a registrable domain in lower-case ASCII, e.g. {@code gmail.com} */
    public boolean isShared(String registrableDomain) {
        return freeMail.contains(registrableDomain) || knownCompanies.directory().isOfficialDomain(registrableDomain);
    }
}
