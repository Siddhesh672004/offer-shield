package com.offershield.common.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository repository;

    @InjectMocks
    private AuditLogService service;

    @Test
    void record_actorActionTarget_savesOneRow() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        service.record(actor, "TPO_ASSIGNMENT_CONSUMED", target);

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNotNull();
        assertThat(saved.getValue().getActorId()).isEqualTo(actor);
        assertThat(saved.getValue().getAction()).isEqualTo("TPO_ASSIGNMENT_CONSUMED");
        assertThat(saved.getValue().getTargetId()).isEqualTo(target);
    }

    @Test
    void record_noTarget_savesRowWithNullTarget() {
        UUID actor = UUID.randomUUID();

        service.record(actor, "LOGIN", null);

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTargetId()).isNull();
    }
}
