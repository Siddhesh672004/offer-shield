package com.offershield.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Transient;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Common columns of every table: a UUIDv7 key assigned in the constructor, plus created/updated timestamps.
 * Implements {@link Persistable} so {@code save()} inserts a new entity directly instead of first running a
 * SELECT (Spring Data cannot tell "new" from an assigned id otherwise).
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    /** For JPA only. */
    protected BaseEntity() {
    }

    protected BaseEntity(UUID id) {
        this.id = id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    /**
     * Flips on persist() itself, not at flush: with an assigned id the INSERT (and {@code @PostPersist}) waits for the
     * flush, and until then Spring Data would treat the entity as new and silently skip {@code delete()}.
     */
    @PrePersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
