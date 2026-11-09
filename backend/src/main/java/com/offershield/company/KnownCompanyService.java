package com.offershield.company;

import com.offershield.analysis.lookup.DomainValidator;
import com.offershield.analysis.lookup.ValidatedDomain;
import com.offershield.common.FreeMailDomains;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.web.BadRequestException;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.NotFoundException;
import com.offershield.common.web.UnprocessableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The admin-only global known-company list (PRD §4 F8, D-24). Each official domain must pass the SSRF guard's checks
 * ({@link DomainValidator}: a public domain name, no IP, port, path, scheme or local name) and is stored as its
 * registrable domain in lower-case ASCII, so {@code careers.example.com} is saved as {@code example.com}. A free-mail
 * provider is never accepted (D-08: otherwise a Gmail sender would count as "official"). A domain belongs to one
 * company; a company keeps 1–{@value #MAX_DOMAINS} domains. Every change is audit-logged (R-E) and evicts the cached
 * list the analysis reads ({@link KnownCompanies}), so the next letter sees it.
 */
@Service
public class KnownCompanyService {

    static final int MAX_DOMAINS = 10;
    static final String CREATED = "KNOWN_COMPANY_CREATED";
    static final String DELETED = "KNOWN_COMPANY_DELETED";
    static final String DOMAIN_ADDED = "KNOWN_COMPANY_DOMAIN_ADDED";
    static final String DOMAIN_REMOVED = "KNOWN_COMPANY_DOMAIN_REMOVED";

    private static final String NOT_FOUND = "Known company not found.";

    private final KnownCompanyRepository companies;
    private final KnownCompanyDomainRepository domains;
    private final DomainValidator domainValidator;
    private final FreeMailDomains freeMail;
    private final AuditLogService auditLog;
    private final KnownCompanies knownCompanies;

    KnownCompanyService(KnownCompanyRepository companies, KnownCompanyDomainRepository domains,
                        DomainValidator domainValidator, FreeMailDomains freeMail, AuditLogService auditLog,
                        KnownCompanies knownCompanies) {
        this.companies = companies;
        this.domains = domains;
        this.domainValidator = domainValidator;
        this.freeMail = freeMail;
        this.auditLog = auditLog;
        this.knownCompanies = knownCompanies;
    }

    /** Every company ordered by name, each with its domains sorted. */
    @Transactional(readOnly = true)
    public List<KnownCompanyResponse> list() {
        Map<UUID, List<KnownCompanyDomain>> byCompany = domains.findAllByOrderByDomainAsc().stream()
                .collect(Collectors.groupingBy(KnownCompanyDomain::getCompanyId));
        return companies.findAllByOrderByNameAsc().stream()
                .map(company -> KnownCompanyResponse.from(company, byCompany.getOrDefault(company.getId(), List.of())))
                .toList();
    }

    /**
     * Name unique ignoring case and accents (column collation), domains unique overall: the pre-checks give a clean
     * 409, and the unique keys catch two admins adding the same thing at once.
     */
    @Transactional
    public KnownCompanyResponse create(CreateKnownCompanyRequest request, UUID adminId) {
        List<String> official = request.domains().stream().map(this::officialDomain).distinct().sorted().toList();
        if (companies.existsByNameIgnoreCase(request.name())) {
            throw companyExists();
        }
        for (String domain : official) {
            if (domains.existsByDomain(domain)) {
                throw domainTaken();
            }
        }
        KnownCompany company;
        try {
            company = companies.saveAndFlush(KnownCompany.create(request.name()));
        } catch (DataIntegrityViolationException ex) {
            throw companyExists();
        }
        List<KnownCompanyDomain> saved;
        try {
            saved = domains.saveAllAndFlush(official.stream()
                    .map(domain -> KnownCompanyDomain.create(company.getId(), domain)).toList());
        } catch (DataIntegrityViolationException ex) {
            throw domainTaken();
        }
        auditLog.record(adminId, CREATED, company.getId());
        knownCompanies.evict();
        return KnownCompanyResponse.from(company, saved);
    }

    /** Removes the company and (database cascade) its domains. */
    @Transactional
    public void delete(UUID companyId, UUID adminId) {
        KnownCompany company = companies.findForUpdate(companyId).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        companies.delete(company);
        companies.flush();
        auditLog.record(adminId, DELETED, companyId);
        knownCompanies.evict();
    }

    @Transactional
    public KnownCompanyResponse addDomain(UUID companyId, AddKnownCompanyDomainRequest request, UUID adminId) {
        String domain = officialDomain(request.domain());
        KnownCompany company = companies.findForUpdate(companyId).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        if (domains.existsByDomain(domain)) {
            throw domainTaken();
        }
        if (domains.countByCompanyId(companyId) >= MAX_DOMAINS) {
            throw new UnprocessableException("TOO_MANY_DOMAINS",
                    "A company can have at most " + MAX_DOMAINS + " official domains.");
        }
        try {
            domains.saveAndFlush(KnownCompanyDomain.create(companyId, domain));
        } catch (DataIntegrityViolationException ex) {
            throw domainTaken();
        }
        auditLog.record(adminId, DOMAIN_ADDED, companyId);
        knownCompanies.evict();
        return KnownCompanyResponse.from(company, domains.findByCompanyIdOrderByDomainAsc(companyId));
    }

    /** The last domain can't be removed: a company without one would make C3 flag every sender (delete it instead). */
    @Transactional
    public KnownCompanyResponse removeDomain(UUID companyId, String domain, UUID adminId) {
        KnownCompany company = companies.findForUpdate(companyId).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        KnownCompanyDomain row = domains.findByCompanyIdAndDomain(companyId, lookupKey(domain))
                .orElseThrow(() -> new NotFoundException("This domain is not listed for the company."));
        if (domains.countByCompanyId(companyId) <= 1) {
            throw new ConflictException("LAST_DOMAIN",
                    "A company needs at least one official domain. Delete the company instead.");
        }
        domains.delete(row);
        domains.flush();
        auditLog.record(adminId, DOMAIN_REMOVED, companyId);
        knownCompanies.evict();
        return KnownCompanyResponse.from(company, domains.findByCompanyIdOrderByDomainAsc(companyId));
    }

    /** The registrable domain an admin's input stands for; 400 when it isn't a public domain name, 422 for free mail. */
    private String officialDomain(String raw) {
        String registrable = domainValidator.validate(raw).map(ValidatedDomain::registrableDomain)
                .orElseThrow(() -> new BadRequestException("INVALID_DOMAIN",
                        "Enter a public domain name such as example.com (no @, http://, path, port or IP address)."));
        if (freeMail.contains(registrable)) {
            throw new UnprocessableException("FREE_MAIL_DOMAIN",
                    "A free email service's domain can't be a company's official domain.");
        }
        return registrable;
    }

    /** The stored form of a domain in a URL path; an invalid one simply won't be found. */
    private String lookupKey(String raw) {
        return domainValidator.validate(raw).map(ValidatedDomain::registrableDomain)
                .orElse(raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT));
    }

    private static ConflictException companyExists() {
        return new ConflictException("COMPANY_EXISTS", "A company with this name is already on the list.");
    }

    private static ConflictException domainTaken() {
        return new ConflictException("DOMAIN_TAKEN", "This domain is already listed for a company.");
    }
}
