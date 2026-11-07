package com.offershield.company;

import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A genuine company on the admin's global list (PRD §4 F8, D-24). Its official domains are {@link KnownCompanyDomain}s. */
@Entity
@Table(name = "known_company")
public class KnownCompany extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** For JPA only. */
    protected KnownCompany() {
    }

    private KnownCompany(String name) {
        super(Ids.newId());
        this.name = name;
    }

    static KnownCompany create(String name) {
        return new KnownCompany(name);
    }

    public String getName() {
        return name;
    }
}
