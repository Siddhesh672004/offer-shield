package com.offershield.college;

import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "college")
public class College extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "city", length = 100)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CollegeStatus status;

    /** Optimistic lock; decisions send it back as {@code If-Match} (stale version gives 409). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** For JPA only. */
    protected College() {
    }

    private College(String name, String city) {
        super(Ids.newId());
        this.name = name;
        this.city = city;
        this.status = CollegeStatus.ACTIVE;
    }

    public static College create(String name, String city) {
        return new College(name, city);
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public CollegeStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }
}
