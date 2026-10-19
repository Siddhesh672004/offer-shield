package com.offershield.common.audit;

import com.offershield.common.Ids;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** One immutable row per TPO/admin decision (R-E). Never holds letter text or personal data. */
@Entity
@Table(name = "audit_log")
public class AuditLog extends BaseEntity {

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, updatable = false, length = 64)
    private String action;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    /** For JPA only. */
    protected AuditLog() {
    }

    private AuditLog(UUID actorId, String action, UUID targetId) {
        super(Ids.newId());
        this.actorId = actorId;
        this.action = action;
        this.targetId = targetId;
    }

    public static AuditLog of(UUID actorId, String action, UUID targetId) {
        return new AuditLog(actorId, action, targetId);
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public UUID getTargetId() {
        return targetId;
    }
}
