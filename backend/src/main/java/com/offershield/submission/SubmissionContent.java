package com.offershield.submission;

import com.offershield.common.SenderSource;

import java.util.UUID;

/**
 * A decrypted submission for the analysis consumer (P6): the letter text plus the optional typed details.
 * {@code senderDomain} follows D-33.6 (typed sender email, else the first email address in the letter) and
 * {@code senderSource} says which of the two it is (P13, S2; {@code null} without a sender domain).
 * {@link #toString()} never shows personal data.
 */
public record SubmissionContent(UUID submissionId, UUID ownerUserId, UUID collegeId, SourceType sourceType,
                                String text, String senderEmail, String senderDomain, String recruiterPhone,
                                String upiId, SenderSource senderSource) {

    public SubmissionContent {
        senderSource = senderDomain == null ? null
                : senderSource != null ? senderSource : SenderSource.of(senderEmail, senderDomain);
    }

    /** Without a stored source (a row saved before P13): derived from whether a sender email was typed. */
    public SubmissionContent(UUID submissionId, UUID ownerUserId, UUID collegeId, SourceType sourceType, String text,
                             String senderEmail, String senderDomain, String recruiterPhone, String upiId) {
        this(submissionId, ownerUserId, collegeId, sourceType, text, senderEmail, senderDomain, recruiterPhone, upiId,
                null);
    }

    @Override
    public String toString() {
        return "SubmissionContent[submissionId=" + submissionId + ", sourceType=" + sourceType + ", redacted]";
    }
}
