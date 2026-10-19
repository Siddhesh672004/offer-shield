package com.offershield.common.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Writes the shared audit trail. Joins the caller's transaction, so the audit row commits with the decision (R-E). */
@Service
public class AuditLogService {

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(UUID actorId, String action, UUID targetId) {
        repository.save(AuditLog.of(actorId, action, targetId));
    }
}
