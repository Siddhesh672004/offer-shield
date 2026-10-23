package com.offershield.submission;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed download links to the original PDF (PRD §9.1, D-05). This service does <b>no</b> access check: the review and
 * scam-report features call it only after their own scoped lookup (the TPO of the case's college, or the admin for a
 * submission with a scam report), and everyone else gets their 404 before reaching it.
 */
@Service
public class PdfLinkService {

    /** M-07: a link works for 5 minutes. */
    public static final Duration TTL = Duration.ofMinutes(5);

    private final SubmissionRepository submissions;
    private final CloudinaryStore store;

    public PdfLinkService(SubmissionRepository submissions, CloudinaryStore store) {
        this.submissions = submissions;
        this.store = store;
    }

    /** True when the submission's PDF is kept privately and storage is configured, so a link can be made. */
    public boolean isAvailable(UUID submissionId) {
        return store.isConfigured() && submissions.findStoredPdf(submissionId).isPresent();
    }

    /**
     * A link valid for {@link #TTL}; empty when the submission is pasted text, its PDF was not kept (storage off at
     * upload), it was deleted, or storage is not configured now.
     */
    public Optional<PdfLink> linkFor(UUID submissionId) {
        if (!store.isConfigured()) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.now().plus(TTL).truncatedTo(ChronoUnit.SECONDS);
        return submissions.findStoredPdf(submissionId)
                .flatMap(publicId -> store.downloadLink(publicId, expiresAt))
                .map(url -> new PdfLink(url, expiresAt));
    }
}
