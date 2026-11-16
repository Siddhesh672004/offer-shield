package com.offershield.scamreport;

import com.offershield.common.crypto.SealedText;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's report that one of their analysed submissions is a scam (PRD §4 F6, §6.4). The reporter's description is
 * held only encrypted ({@link ScamReportService} seals and opens it). State machine (02 §7): OPEN → CONFIRMED /
 * REJECTED by the admin, once, at the version the admin looked at.
 */
@Entity
@Table(name = "scam_report")
public class ScamReport extends BaseEntity {

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    /** {@code null} once the retention job wiped it, 30 days after the decision (D-06, V22). */
    @Column(name = "description_cipher", updatable = false, columnDefinition = "BLOB")
    private byte[] descriptionCipher;

    @Column(name = "key_version", nullable = false, updatable = false)
    private int keyVersion;

    @Column(name = "amount_asked", updatable = false)
    private Integer amountAsked;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ScamReportStatus status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Optimistic lock; the admin's decision sends it back as {@code If-Match} (a stale version gives 409). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** For JPA only. */
    protected ScamReport() {
    }

    private ScamReport(UUID id, UUID submissionId, UUID reporterUserId, SealedText description, Integer amountAsked) {
        super(id);
        this.submissionId = submissionId;
        this.reporterUserId = reporterUserId;
        this.descriptionCipher = description.bytes();
        this.keyVersion = description.keyVersion();
        this.amountAsked = amountAsked;
        this.status = ScamReportStatus.OPEN;
    }

    /** {@code id} is chosen by the caller because it is the description's AES-GCM associated data. */
    static ScamReport open(UUID id, UUID submissionId, UUID reporterUserId, SealedText description,
                           Integer amountAsked) {
        return new ScamReport(id, submissionId, reporterUserId, description, amountAsked);
    }

    void confirm(UUID adminUserId, Instant at) {
        decide(ScamReportStatus.CONFIRMED, adminUserId, at);
    }

    void reject(UUID adminUserId, Instant at) {
        decide(ScamReportStatus.REJECTED, adminUserId, at);
    }

    boolean isOpen() {
        return status == ScamReportStatus.OPEN;
    }

    private void decide(ScamReportStatus outcome, UUID adminUserId, Instant at) {
        if (!isOpen()) {
            throw new IllegalStateException("The scam report has already been decided");
        }
        this.status = outcome;
        this.decidedBy = adminUserId;
        this.decidedAt = at;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    /** {@code null} once wiped by the retention job (D-06). */
    SealedText getDescription() {
        return descriptionCipher == null ? null : new SealedText(descriptionCipher, keyVersion);
    }

    public Integer getAmountAsked() {
        return amountAsked;
    }

    public ScamReportStatus getStatus() {
        return status;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Long getVersion() {
        return version;
    }
}
