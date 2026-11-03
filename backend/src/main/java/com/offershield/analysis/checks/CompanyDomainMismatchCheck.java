package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import com.offershield.company.KnownCompanyDirectory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * C3, the sender's domain is not the claimed company's official domain (PRD §7.1, §7.2): +20. Source kind 3 (the
 * admin's known-company list, D-24).
 * <ul>
 *   <li>The claimed company comes from the rules: every known company whose name appears in the letter as whole words
 *       ({@link CompanyNames}). The sender passes when its registrable domain is an official domain of any company the
 *       letter names (a subdomain such as {@code careers.example.com} counts).</li>
 *   <li>R-A: only when the rules find no known company does the AI's company name count, and it can only add risk: a
 *       mismatch is TRIGGERED and labelled "AI-extracted"; a match is never reported as PASSED, so the AI alone never
 *       identifies a sender as a company's official domain (P9's copied-letter guard relies on that).</li>
 *   <li>P13 (security review S2): when the sender address was taken from the letter itself (no typed sender email,
 *       D-33.6), its author chose it, so a match is NOT_APPLICABLE ("taken from the letter itself"), never PASSED; a
 *       mismatch still triggers.</li>
 *   <li>Not applicable without a public sender domain (SSRF guard), for a free-mail sender (C2 already counts that,
 *       so there is no double counting) and while the list is empty.</li>
 * </ul>
 */
@Component
public class CompanyDomainMismatchCheck implements RiskCheck {

    static final int POINTS = 20;
    private static final int SOURCE_KIND = 3;
    private static final int MAX_SHOWN = 3;

    private final KnownCompanies knownCompanies;
    private final FreeMailDomains freeMail;

    public CompanyDomainMismatchCheck(KnownCompanies knownCompanies, FreeMailDomains freeMail) {
        this.knownCompanies = knownCompanies;
        this.freeMail = freeMail;
    }

    @Override
    public CheckCode code() {
        return CheckCode.C3;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        KnownCompanyDirectory directory = knownCompanies.directory();
        if (directory.isEmpty()) {
            return CheckResult.notApplicable(CheckCode.C3,
                    "No companies are on the known-company list yet, so there was nothing to compare.", SOURCE_KIND);
        }
        ValidatedDomain domain = letter.domain();
        if (domain == null) {
            return CheckResult.notApplicable(CheckCode.C3,
                    "There is no public sender domain to compare with a company's official domains.", SOURCE_KIND);
        }
        if (freeMail.contains(domain.registrableDomain())) {
            return CheckResult.notApplicable(CheckCode.C3,
                    "The sender uses a free email service; the free email check (C2) already counts that.", SOURCE_KIND);
        }
        List<KnownCompanyDirectory.Company> named = CompanyNames.namedIn(letter.text(), directory.companies());
        if (!named.isEmpty()) {
            return byRules(named, domain, letter.senderFromLetter());
        }
        List<KnownCompanyDirectory.Company> fromAi = CompanyNames.namedIn(letter.ai().companyName(),
                directory.companies());
        if (!fromAi.isEmpty() && fromAi.stream().noneMatch(company -> company.owns(domain.registrableDomain()))) {
            String name = fromAi.get(0).name();
            return new CheckResult(CheckCode.C3, CheckOutcome.TRIGGERED, POINTS, false,
                    "AI-extracted: the letter appears to come from " + name + ", but the sender's domain is not one of "
                            + "the official domains listed for " + name + ". Scammers often pose as well-known employers.",
                    evidence("Employer named in the letter (AI-extracted): ", fromAi, domain), SOURCE_KIND, true);
        }
        return CheckResult.notApplicable(CheckCode.C3,
                "The letter doesn't name a company from the known-company list, so there was nothing to compare.",
                SOURCE_KIND);
    }

    private static CheckResult byRules(List<KnownCompanyDirectory.Company> named, ValidatedDomain domain,
                                       boolean senderFromLetter) {
        for (KnownCompanyDirectory.Company company : named) {
            if (company.owns(domain.registrableDomain())) {
                if (senderFromLetter) {
                    // P13 (S2): the letter's author chose this address, so a match proves nothing; a mismatch still
                    // counts (it can only add risk).
                    return CheckResult.notApplicable(CheckCode.C3, "The sender address was taken from the letter "
                            + "itself, not typed by you, so it can't show the letter came from " + company.name()
                            + ". Add the address the letter was sent from to compare it.", SOURCE_KIND);
                }
                return CheckResult.passed(CheckCode.C3, "The sender's domain is one of the official domains listed for "
                        + company.name() + ". This alone doesn't prove the offer is genuine.", SOURCE_KIND);
            }
        }
        String name = named.get(0).name();
        return CheckResult.triggered(CheckCode.C3, POINTS, "The letter names " + name + ", but the sender's domain is "
                        + "not one of the official domains listed for " + name + ". Scammers often pose as well-known "
                        + "employers.",
                evidence("Letter names ", named, domain), SOURCE_KIND);
    }

    private static String evidence(String prefix, List<KnownCompanyDirectory.Company> companies,
                                   ValidatedDomain domain) {
        String official = companies.stream().flatMap(company -> company.officialDomains().stream()).limit(MAX_SHOWN)
                .collect(Collectors.joining(", "));
        return Evidence.snippet(prefix + CompanyNames.names(companies, MAX_SHOWN) + "; sent from " + domain.host()
                + "; official domains: " + official);
    }
}
