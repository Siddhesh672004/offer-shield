package com.offershield.submission;

import com.offershield.common.SenderSource;
import com.offershield.common.crypto.SealedText;
import com.offershield.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One offer letter submitted by its owner (PRD §10). The text and contact details are only ever held encrypted here;
 * {@link SubmissionService} seals and opens them. The ID is chosen by the service before encryption because it is the
 * AES-GCM associated data.
 */
@Entity
@Table(name = "submission")
public class Submission extends BaseEntity {

    /** D-06: everything about a submission is deleted 30 days after it was made. */
    static final Duration RETENTION = Duration.ofDays(30);

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "college_id", updatable = false)
    private UUID collegeId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_sha256", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 8)
    private SourceType sourceType;

    @Column(name = "content_sha256", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String contentSha256;

    @Column(name = "text_cipher", columnDefinition = "MEDIUMBLOB")
    private byte[] textCipher;

    @Column(name = "contacts_cipher", length = 2048)
    private byte[] contactsCipher;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "sender_domain", length = 253)
    private String senderDomain;

    /** P13 (S2): TYPED or LETTER (D-33.6), {@code null} without a sender domain or for rows saved before P13. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sender_source", length = 8, updatable = false)
    private SenderSource senderSource;

    @Column(name = "cloudinary_public_id", length = 255)
    private String cloudinaryPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SubmissionStatus status;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "last_published_at", nullable = false)
    private Instant lastPublishedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** For JPA only. */
    protected Submission() {
    }

    @SuppressWarnings("java:S107")
    Submission(UUID id, UUID ownerUserId, UUID collegeId, String idempotencyKey, String requestSha256,
               SourceType sourceType, String contentSha256, SealedText text, SealedText contacts, String senderDomain,
               SenderSource senderSource, String cloudinaryPublicId, Instant now) {
        super(id);
        if (contacts != null && contacts.keyVersion() != text.keyVersion()) {
            throw new IllegalArgumentException("text and contacts must be sealed with the same key version");
        }
        this.ownerUserId = ownerUserId;
        this.collegeId = collegeId;
        this.idempotencyKey = idempotencyKey;
        this.requestSha256 = requestSha256;
        this.sourceType = sourceType;
        this.contentSha256 = contentSha256;
        this.textCipher = text.bytes();
        this.contactsCipher = contacts == null ? null : contacts.bytes();
        this.keyVersion = text.keyVersion();
        this.senderDomain = senderDomain;
        this.senderSource = senderDomain == null ? null : senderSource;
        this.cloudinaryPublicId = cloudinaryPublicId;
        this.status = SubmissionStatus.QUEUED;
        this.publishAttempts = 0;
        // The first publish happens right after this row commits.
        this.lastPublishedAt = now;
        this.expiresAt = now.plus(RETENTION);
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getCollegeId() {
        return collegeId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestSha256() {
        return requestSha256;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public SealedText getText() {
        return textCipher == null ? null : new SealedText(textCipher, keyVersion);
    }

    public SealedText getContacts() {
        return contactsCipher == null ? null : new SealedText(contactsCipher, keyVersion);
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public String getSenderDomain() {
        return senderDomain;
    }

    public SenderSource getSenderSource() {
        return senderSource;
    }

    public String getCloudinaryPublicId() {
        return cloudinaryPublicId;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }

    public Instant getLastPublishedAt() {
        return lastPublishedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
