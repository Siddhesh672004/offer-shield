package com.offershield.analysis.checks;

import com.offershield.analysis.lookup.DomainNormalizer;
import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import com.offershield.company.KnownCompanyDirectory;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * C4, look-alike domain (PRD §7.1, §7.2 +30; D-03 brand rule). Source kind 3 (the admin's known-company list).
 * <ol>
 *   <li>Each known company's <b>brands</b> are its core name without spaces or punctuation ({@code Northwind Analytics}
 *       → {@code northwindanalytics}) and the first label of each official domain ({@code infosys.com} →
 *       {@code infosys}), all as look-alike skeletons ({@link DomainNormalizer}). Brands shorter than
 *       {@value #MIN_BRAND_LENGTH} characters (TCS, IBM) are not used: they would match unrelated firms; C3 still
 *       flags a letter that names such a company from another domain.</li>
 *   <li>The sender's labels in front of the public suffix (subdomains included) are turned into skeletons too; a
 *       label is compared whole (hyphens removed) and hyphen-part by hyphen-part.</li>
 *   <li>Flagged when a label <b>contains</b> a brand ({@code inf0sys-careers} → {@code lnfosyscareers} ⊃
 *       {@code lnfosys}), or a label or part is within {@link #allowedEdits} edits of it (insert, delete, change, or
 *       swap two neighbours): 1 edit for brands of 5–7 characters, 2 for 8 or more (D-03's "≤ 2 edits").</li>
 *   <li>Never flagged (D-08): an official domain of <b>any</b> known company, its subdomains, and free-mail senders
 *       (C2 counts those). Not applicable without a public sender domain or while the list is empty.</li>
 * </ol>
 * Documented limitation (D-03 "contains"): an unrelated name that contains a brand (xyzinfosystems.com) is flagged as
 * well; +30 on its own is Medium, never High.
 */
@Component
public class LookalikeDomainCheck implements RiskCheck {

    static final int POINTS = 30;
    static final int MIN_BRAND_LENGTH = 5;
    private static final int LONG_BRAND_LENGTH = 8;
    private static final int SOURCE_KIND = 3;
    private static final int MAX_SHOWN = 3;
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");

    private final KnownCompanies knownCompanies;
    private final FreeMailDomains freeMail;

    public LookalikeDomainCheck(KnownCompanies knownCompanies, FreeMailDomains freeMail) {
        this.knownCompanies = knownCompanies;
        this.freeMail = freeMail;
    }

    @Override
    public CheckCode code() {
        return CheckCode.C4;
    }

    @Override
    public CheckResult evaluate(Letter letter) {
        KnownCompanyDirectory directory = knownCompanies.directory();
        if (directory.isEmpty()) {
            return CheckResult.notApplicable(CheckCode.C4,
                    "No companies are on the known-company list yet, so there was nothing to compare.", SOURCE_KIND);
        }
        ValidatedDomain domain = letter.domain();
        if (domain == null) {
            return CheckResult.notApplicable(CheckCode.C4,
                    "There is no public sender domain to compare with the known companies.", SOURCE_KIND);
        }
        if (freeMail.contains(domain.registrableDomain())) {
            return CheckResult.notApplicable(CheckCode.C4,
                    "The sender uses a free email service; the free email check (C2) already counts that.", SOURCE_KIND);
        }
        Optional<KnownCompanyDirectory.Company> owner = directory.ownerOf(domain.registrableDomain());
        if (owner.isPresent()) {
            return CheckResult.passed(CheckCode.C4, "The sender's domain is listed as an official domain of "
                    + owner.get().name() + ", so it is not treated as a look-alike.", SOURCE_KIND);
        }
        SenderParts sender = SenderParts.of(domain);
        for (KnownCompanyDirectory.Company company : directory.companies()) {
            if (brands(company).stream().anyMatch(sender::resemble)) {
                return triggered(company, domain);
            }
        }
        return CheckResult.passed(CheckCode.C4, "The sender's domain doesn't look like any company on the known-company "
                + "list. Only companies on that list are compared.", SOURCE_KIND);
    }

    /** The company's brands as skeletons of at least {@value #MIN_BRAND_LENGTH} characters. */
    static Set<String> brands(KnownCompanyDirectory.Company company) {
        Set<String> brands = new LinkedHashSet<>();
        brands.add(squash(DomainNormalizer.skeleton(CompanyNames.core(company.name()))));
        for (String official : company.officialDomains()) {
            brands.add(squash(DomainNormalizer.skeleton(official.substring(0, official.indexOf('.')))));
        }
        brands.removeIf(brand -> brand.length() < MIN_BRAND_LENGTH);
        return brands;
    }

    /** 1 edit for a brand of 5–7 characters, 2 for a longer one. */
    static int allowedEdits(String brand) {
        return brand.length() >= LONG_BRAND_LENGTH ? 2 : 1;
    }

    /**
     * Optimal-string-alignment distance: inserts, deletes, changes and swaps of two neighbouring characters each count
     * as one edit.
     */
    static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** The sender's labels as skeletons: each whole label (hyphens removed) and each hyphen-separated part. */
    private record SenderParts(List<String> labels, List<String> parts) {

        static SenderParts of(ValidatedDomain domain) {
            List<String> labels = new ArrayList<>();
            List<String> parts = new ArrayList<>();
            for (String label : DomainNormalizer.labelsBeforeSuffix(domain)) {
                String skeleton = DomainNormalizer.skeleton(label);
                labels.add(squash(skeleton));
                for (String part : skeleton.split("-")) {
                    if (!part.isEmpty()) {
                        parts.add(part);
                    }
                }
            }
            return new SenderParts(labels, parts);
        }

        boolean resemble(String brand) {
            return labels.stream().anyMatch(label -> label.contains(brand) || near(label, brand))
                    || parts.stream().anyMatch(part -> near(part, brand));
        }

        private static boolean near(String candidate, String brand) {
            int allowed = allowedEdits(brand);
            return Math.abs(candidate.length() - brand.length()) <= allowed && editDistance(candidate, brand) <= allowed;
        }
    }

    private static CheckResult triggered(KnownCompanyDirectory.Company company, ValidatedDomain domain) {
        String shown = IDN.toUnicode(domain.host(), IDN.ALLOW_UNASSIGNED);
        String sender = shown.equals(domain.host()) ? shown : shown + " (" + domain.host() + ")";
        String official = company.officialDomains().stream().limit(MAX_SHOWN).collect(Collectors.joining(", "));
        return CheckResult.triggered(CheckCode.C4, POINTS, "The sender's domain looks like the name of "
                        + company.name() + ", a company on the known-company list, but it is not one of that company's "
                        + "official domains. Scammers use look-alike domains to pose as real employers.",
                Evidence.snippet(sender + " looks like " + company.name() + "; official domains: " + official),
                SOURCE_KIND);
    }

    private static String squash(String skeleton) {
        return NOT_LETTER_OR_DIGIT.matcher(skeleton).replaceAll("");
    }
}
