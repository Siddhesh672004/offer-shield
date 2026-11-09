package com.offershield.company;

import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** One official email domain of a {@link KnownCompany}: a registrable domain in lower-case ASCII, unique overall. */
@Entity
@Table(name = "known_company_domain")
public class KnownCompanyDomain extends BaseEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "domain", nullable = false, updatable = false, length = 253)
    private String domain;

    /** For JPA only. */
    protected KnownCompanyDomain() {
    }

    private KnownCompanyDomain(UUID companyId, String domain) {
        super(Ids.newId());
        this.companyId = companyId;
        this.domain = domain;
    }

    static KnownCompanyDomain create(UUID companyId, String domain) {
        return new KnownCompanyDomain(companyId, domain);
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getDomain() {
        return domain;
    }
}
