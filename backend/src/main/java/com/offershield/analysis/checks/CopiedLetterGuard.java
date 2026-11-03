package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import com.offershield.company.KnownCompanyDirectory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The copied-letter guard of PRD §7.3 (R-A). Scammers copy a genuine company's letter word for word, so once such a
 * copy is confirmed, a later genuine letter from that company matches its SimHash. C11 therefore forces High only when
 * the sender is not on an official domain of a company the letter itself names, and (P13) unverified wording-only
 * reports don't count for C12 when it is.
 *
 * <p>The company must be identified by the rules: a known company whose name appears in the letter's text
 * ({@link CompanyNames}, the same rule as C3's PASSED), with the sender's registrable domain among its official
 * domains. The AI's company name ({@link AiFindings#companyName()}) is never used here, so the AI can never lower a
 * score. A free-mail sender is never official (D-08).
 *
 * <p>P13 (security review S2): when the sender address was taken from the letter itself (no typed sender email,
 * D-33.6), the letter's author chose it, for example by writing the official address first and a Gmail address
 * later. Then the guard applies only when <b>every</b> email address in the letter is on an official domain of that
 * company and the letter sends nobody to WhatsApp or Telegram (so C8 cannot trigger either).
 */
@Component
public class CopiedLetterGuard {

    private final KnownCompanies knownCompanies;
    private final FreeMailDomains freeMail;

    public CopiedLetterGuard(KnownCompanies knownCompanies, FreeMailDomains freeMail) {
        this.knownCompanies = knownCompanies;
        this.freeMail = freeMail;
    }

    /** The company named in the letter's text whose official domain the sender uses, if any. */
    public Optional<KnownCompanyDirectory.Company> officialSender(Letter letter) {
        ValidatedDomain domain = letter.domain();
        if (domain == null || freeMail.contains(domain.registrableDomain())) {
            return Optional.empty();
        }
        KnownCompanyDirectory directory = knownCompanies.directory();
        if (directory.isEmpty()) {
            return Optional.empty();
        }
        List<KnownCompanyDirectory.Company> named = CompanyNames.namedIn(letter.text(), directory.companies());
        Optional<KnownCompanyDirectory.Company> official = named.stream()
                .filter(company -> company.owns(domain.registrableDomain())).findFirst();
        if (official.isPresent() && letter.senderFromLetter() && !onlyOfficialContacts(letter.text(), official.get())) {
            return Optional.empty();
        }
        return official;
    }

    /** Every email address in the letter is on one of the company's official domains, and there is no messenger. */
    private static boolean onlyOfficialContacts(String text, KnownCompanyDirectory.Company company) {
        Set<String> hosts = EmailDomains.in(text);
        return !hosts.isEmpty() && hosts.stream().allMatch(host -> officialHost(host, company))
                && !OffPlatformContactCheck.mentionsMessenger(text);
    }

    /** The host is an official domain or a subdomain of one ({@code careers.example.com}). */
    private static boolean officialHost(String host, KnownCompanyDirectory.Company company) {
        return company.officialDomains().stream().anyMatch(official -> host.equals(official)
                || host.endsWith("." + official));
    }
}
