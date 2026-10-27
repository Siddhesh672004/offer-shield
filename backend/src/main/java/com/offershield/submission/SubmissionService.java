package com.offershield.submission;

import com.offershield.common.Hashing;
import com.offershield.common.Ids;
import com.offershield.common.SenderSource;
import com.offershield.common.concurrency.UserLocks;
import com.offershield.common.crypto.SealedText;
import com.offershield.common.crypto.TextCipher;
import com.offershield.common.logging.LogContext;
import com.offershield.common.web.BadRequestException;
import com.offershield.common.web.NotFoundException;
import com.offershield.common.web.ServiceUnavailableException;
import com.offershield.common.web.TooManyRequestsException;
import com.offershield.common.web.UnprocessableException;
import com.offershield.membership.MembershipResponse;
import com.offershield.membership.MembershipService;
import com.offershield.membership.MembershipStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Upload of an offer letter (PRD §4 F3, §6.2): Idempotency-Key replay (D-33.2), the DB-count limit (D-12), validation
 * and text extraction (§14.4), private PDF storage after validation (D-05), AES-GCM encryption (§10.1), then one short
 * transaction that re-checks the limit and saves the QUEUED row. Only after it commits is {@code submission.created}
 * published (D-28), outside any transaction. The slow steps (PDF parsing, Cloudinary, the broker) therefore never hold
 * a database connection. Owners read only their own rows (404 otherwise, PRD §14.3).
 *
 * <p>P13: one PDF read at a time per user and a per-user bucket of failed reads ({@link PdfReadGuard}); the insert
 * transaction holds the user's lock ({@link UserLocks}) around "same key already saved? → replay; count; insert", so
 * parallel uploads cannot pass the limit and a same-key double click at the 5th upload gets the first click's answer.
 */
@Service
public class SubmissionService {

    static final int MAX_UPLOADS_PER_WINDOW = 5;
    static final Duration UPLOAD_WINDOW = Duration.ofMinutes(10);
    static final int MAX_LISTED = 100;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,100}");
    /**
     * Flat, bounded character classes only: a nested repeated group would backtrack badly or overflow the regex
     * engine's stack on hostile text. The captured domain is checked by {@link #validDomain(String)}.
     */
    private static final Pattern EMAIL_IN_TEXT = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9.-]{1,253})");
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final SubmissionRepository submissions;
    private final FileValidator fileValidator;
    private final TextCipher textCipher;
    private final CloudinaryStore cloudinaryStore;
    private final MembershipService membershipService;
    private final SubmissionEventPublisher publisher;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final UserLocks uploadLocks = new UserLocks();
    private final PdfReadGuard pdfReadGuard = new PdfReadGuard();

    @SuppressWarnings("java:S107")
    public SubmissionService(SubmissionRepository submissions, FileValidator fileValidator, TextCipher textCipher,
                             CloudinaryStore cloudinaryStore, MembershipService membershipService,
                             SubmissionEventPublisher publisher, PlatformTransactionManager transactionManager,
                             JsonMapper jsonMapper) {
        this.submissions = submissions;
        this.fileValidator = fileValidator;
        this.textCipher = textCipher;
        this.cloudinaryStore = cloudinaryStore;
        this.membershipService = membershipService;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        // Always its own transaction, so "publish after commit" holds even if a caller already has one open.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jsonMapper = jsonMapper;
    }

    public SubmissionAccepted submit(UUID ownerUserId, NewSubmission request) {
        String key = requireIdempotencyKey(request.idempotencyKey());
        MultipartFile file = request.file();
        String pastedText = normaliseText(request.text());
        if ((file == null) == (pastedText == null)) {
            throw new BadRequestException("FILE_OR_TEXT", "Send either a PDF file or the pasted text of the letter.");
        }
        SourceType sourceType = file != null ? SourceType.PDF : SourceType.TEXT;
        byte[] pdfBytes = null;
        if (file != null) {
            fileValidator.checkSize(file);
            pdfBytes = FileValidator.read(file);
        }
        SubmissionContacts contacts = new SubmissionContacts(request.senderEmail(), request.recruiterPhone(),
                request.upiId());
        String requestSha256 = requestHash(sourceType, pdfBytes != null ? Hashing.sha256Hex(pdfBytes)
                : Hashing.sha256Hex(pastedText), contacts);

        Optional<SubmissionAccepted> replay = replay(ownerUserId, key, requestSha256);
        if (replay.isPresent()) {
            return replay.get();
        }
        enforceUploadLimit(ownerUserId);

        String text = pastedText;
        if (pdfBytes != null) {
            text = readPdf(ownerUserId, pdfBytes);
        }
        String publicId = storePdf(ownerUserId, key, requestSha256, pdfBytes);

        UUID id = Ids.newId();
        MDC.put(LogContext.SUBMISSION_ID, id.toString());
        Instant now = Instant.now();
        UUID collegeId = approvedCollege(ownerUserId);
        String contentSha256 = Hashing.sha256Hex(text);
        String senderDomain = senderDomain(contacts.senderEmail(), text);
        Submission submission = new Submission(id, ownerUserId, collegeId, key, requestSha256, sourceType,
                contentSha256, textCipher.encrypt(text, textAad(id)), sealContacts(id, contacts),
                senderDomain, SenderSource.of(contacts.senderEmail(), senderDomain), publicId, now);
        Optional<SubmissionAccepted> sameKeyCommitted;
        try {
            sameKeyCommitted = transaction.execute(status -> {
                // Count and insert under the user's lock (S3), so parallel uploads cannot pass the limit together.
                uploadLocks.lockUntilTransactionEnds(ownerUserId);
                // A click with the same key may have committed while this one was being read (CR2): answer with its
                // submission (same content means the same PDF public ID, which its row references, so it is kept).
                Optional<SubmissionAccepted> committed = replay(ownerUserId, key, requestSha256);
                if (committed.isPresent()) {
                    return committed;
                }
                // Again, now that the slow steps are done: other uploads may have used the last slots meanwhile.
                enforceUploadLimit(ownerUserId);
                submissions.saveAndFlush(submission);
                return Optional.empty();
            });
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request with the same key committed first (UNIQUE owner + key): answer like a replay.
            // Same content means the same PDF public ID, which the winner's row references, so it is kept.
            MDC.remove(LogContext.SUBMISSION_ID);
            Optional<SubmissionAccepted> winner;
            try {
                winner = replay(ownerUserId, key, requestSha256);
            } catch (RuntimeException keyReused) {
                deletePdfQuietly(publicId);
                throw keyReused;
            }
            if (winner.isPresent()) {
                return winner.get();
            }
            deletePdfQuietly(publicId);
            throw ex;
        } catch (RuntimeException ex) {
            // Over the limit, the key reused for other content (a different public ID), or a database error: no row
            // references this PDF.
            deletePdfQuietly(publicId);
            throw ex;
        }
        if (sameKeyCommitted != null && sameKeyCommitted.isPresent()) {
            return sameKeyCommitted.get();
        }
        log.info("submission queued sourceType={}", sourceType);
        // After the commit and outside any transaction, so no database connection waits for the broker (D-28).
        publisher.publish(new SubmissionQueued(id, ownerUserId, collegeId, sourceType, contentSha256, now));
        return new SubmissionAccepted(id);
    }

    /** The owner's submission, or 404 for a missing or someone else's ID (same response for both). */
    @Transactional(readOnly = true)
    public SubmissionResponse get(UUID ownerUserId, UUID submissionId) {
        return submissions.findViewByIdAndOwnerUserId(submissionId, ownerUserId)
                .map(SubmissionResponse::from)
                .orElseThrow(() -> new NotFoundException("Submission not found"));
    }

    /** The owner's latest submissions, newest first (at most {@value #MAX_LISTED}; deleted ones are left out). */
    @Transactional(readOnly = true)
    public List<SubmissionResponse> list(UUID ownerUserId) {
        return submissions.findViewsByOwner(ownerUserId, Limit.of(MAX_LISTED)).stream()
                .map(SubmissionResponse::from)
                .toList();
    }

    /**
     * Decrypted text and contact details for the analysis consumer (P6), which receives only IDs over Kafka. Empty
     * when the row is gone or its text was already wiped by retention.
     */
    @Transactional(readOnly = true)
    public Optional<SubmissionContent> findContent(UUID submissionId) {
        return submissions.findById(submissionId)
                .filter(submission -> submission.getText() != null)
                .map(submission -> {
                    String text = textCipher.decrypt(submission.getText(), textAad(submission.getId()));
                    SubmissionContacts contacts = openContacts(submission);
                    return new SubmissionContent(submission.getId(), submission.getOwnerUserId(),
                            submission.getCollegeId(), submission.getSourceType(), text, contacts.senderEmail(),
                            submission.getSenderDomain(), contacts.recruiterPhone(), contacts.upiId(),
                            submission.getSenderSource());
                });
    }

    private Optional<SubmissionAccepted> replay(UUID ownerUserId, String key, String requestSha256) {
        return submissions.findStoredRequest(ownerUserId, key).map(stored -> {
            if (!stored.requestSha256().equals(requestSha256)) {
                throw new UnprocessableException("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different upload. Send it with a new key.");
            }
            MDC.put(LogContext.SUBMISSION_ID, stored.id().toString());
            log.info("submission replayed");
            return new SubmissionAccepted(stored.id());
        });
    }

    /**
     * The PDF's text (PRD §14.4), one read at a time per user; a file that fails to read counts against the user's
     * failed-read bucket (S1), unless the reader was merely busy (not the file's fault).
     */
    private String readPdf(UUID ownerUserId, byte[] pdfBytes) {
        pdfReadGuard.startReading(ownerUserId);
        boolean failed = true;
        try {
            String text = fileValidator.validate(pdfBytes).text();
            failed = false;
            return text;
        } catch (ServiceUnavailableException ex) {
            failed = !"SERVER_BUSY".equals(ex.errorCode());
            throw ex;
        } finally {
            pdfReadGuard.finishReading(ownerUserId, failed);
        }
    }

    /** D-12: at most 5 new submissions per user in any 10 minutes, counted in the database. */
    private void enforceUploadLimit(UUID ownerUserId) {
        Instant now = Instant.now();
        Instant since = now.minus(UPLOAD_WINDOW);
        if (submissions.countByOwnerUserIdAndCreatedAtAfter(ownerUserId, since) < MAX_UPLOADS_PER_WINDOW) {
            return;
        }
        Instant oldest = submissions.findOldestCreatedAtAfter(ownerUserId, since).orElse(now);
        Duration retryAfter = Duration.between(now, oldest.plus(UPLOAD_WINDOW));
        throw new TooManyRequestsException("UPLOAD_LIMIT",
                "You can submit up to 5 letters every 10 minutes. Please wait a few minutes and try again.",
                retryAfter.isNegative() ? Duration.ZERO : retryAfter);
    }

    /** Only after validation; skipped (and logged without personal data) when storage is not configured. */
    private String storePdf(UUID ownerUserId, String key, String requestSha256, byte[] pdfBytes) {
        if (pdfBytes == null) {
            return null;
        }
        if (!cloudinaryStore.isConfigured()) {
            log.info("pdf storage skipped: not configured");
            return null;
        }
        String publicId = CloudinaryStore.publicIdFor(ownerUserId, key, requestSha256);
        cloudinaryStore.upload(publicId, pdfBytes);
        return publicId;
    }

    /** Best effort: a PDF whose submission was not saved must not stay in storage. */
    private void deletePdfQuietly(String publicId) {
        if (publicId == null) {
            return;
        }
        try {
            cloudinaryStore.delete(publicId);
        } catch (RuntimeException ex) {
            log.warn("pdf cleanup failed: {}", ex.getClass().getSimpleName());
        }
    }

    private UUID approvedCollege(UUID ownerUserId) {
        return membershipService.findForUser(ownerUserId)
                .filter(membership -> membership.status() == MembershipStatus.APPROVED)
                .map(MembershipResponse::collegeId)
                .orElse(null);
    }

    private SealedText sealContacts(UUID id, SubmissionContacts contacts) {
        if (contacts.isEmpty()) {
            return null;
        }
        return textCipher.encrypt(jsonMapper.writeValueAsString(contacts), contactsAad(id));
    }

    private SubmissionContacts openContacts(Submission submission) {
        SealedText sealed = submission.getContacts();
        if (sealed == null) {
            return new SubmissionContacts(null, null, null);
        }
        return jsonMapper.readValue(textCipher.decrypt(sealed, contactsAad(submission.getId())),
                SubmissionContacts.class);
    }

    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (for example a random UUID) with each new upload.");
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    "The Idempotency-Key must be 8 to 100 letters, digits, '-', '_', '.' or ':'.");
        }
        return key;
    }

    private static String normaliseText(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace("\u0000", "").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** What makes two requests "the same" for Idempotency-Key replay: source, content and every optional field. */
    private static String requestHash(SourceType sourceType, String contentHash, SubmissionContacts contacts) {
        return Hashing.sha256Hex(String.join("\n", "v1", sourceType.name(), contentHash,
                orEmpty(contacts.senderEmail()), orEmpty(contacts.recruiterPhone()), orEmpty(contacts.upiId())));
    }

    /** D-33.6: the typed sender email's domain, else the domain of the first email address in the letter. */
    static String senderDomain(String typedSenderEmail, String letterText) {
        if (typedSenderEmail != null) {
            int at = typedSenderEmail.lastIndexOf('@');
            return at < 0 ? null : normaliseDomain(typedSenderEmail.substring(at + 1));
        }
        Matcher matcher = EMAIL_IN_TEXT.matcher(letterText);
        while (matcher.find()) {
            String domain = validDomain(matcher.group(1));
            if (domain != null) {
                return domain;
            }
        }
        return null;
    }

    /** A host name with at least two labels and a letters-only top-level label, or {@code null}. */
    private static String validDomain(String candidate) {
        String domain = candidate.toLowerCase(Locale.ROOT);
        int end = domain.length();
        while (end > 0 && (domain.charAt(end - 1) == '.' || domain.charAt(end - 1) == '-')) {
            end--; // sentence punctuation after an address: "write to hr@acme.example."
        }
        domain = domain.substring(0, end);
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return null;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return null;
            }
        }
        String topLevel = labels[labels.length - 1];
        boolean lettersOnly = topLevel.chars().allMatch(ch -> (ch >= 'a' && ch <= 'z'));
        return topLevel.length() >= 2 && lettersOnly ? domain : null;
    }

    private static String normaliseDomain(String domain) {
        String normalised = domain.strip().toLowerCase(Locale.ROOT);
        while (normalised.endsWith(".")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return normalised.isEmpty() || normalised.length() > MAX_DOMAIN_LENGTH ? null : normalised;
    }

    private static byte[] textAad(UUID submissionId) {
        return submissionId.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] contactsAad(UUID submissionId) {
        return (submissionId + ":contacts").getBytes(StandardCharsets.UTF_8);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
