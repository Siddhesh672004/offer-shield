package com.offershield.submission;

import com.offershield.common.crypto.SealedText;
import com.offershield.common.crypto.TextCipher;
import com.offershield.common.web.ApiException;
import com.offershield.common.web.ServiceUnavailableException;
import com.offershield.common.web.TooManyRequestsException;
import com.offershield.common.web.UnprocessableException;
import com.offershield.common.web.UnsupportedMediaTypeException;
import com.offershield.membership.MembershipResponse;
import com.offershield.membership.MembershipService;
import com.offershield.membership.MembershipStatus;
import com.offershield.support.LogCapture;
import com.offershield.support.NoOpTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Upload orchestration (P5-01..05): idempotency, limits, validation before storage, encryption, publish trigger. */
class SubmissionServiceTest {

    private static final UUID OWNER = UUID.fromString("0190c9a0-0000-7000-8000-000000000001");
    private static final String KEY = "6f1c2a9e-key-0001";
    private static final String LETTER = "Dear Candidate, you are selected as Data Analyst at Example Fictional Corp. "
            + "Contact hr.team@fictional-careers.example for joining. Pay the refundable deposit today.";

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final FileValidator fileValidator = mock(FileValidator.class);
    private final CloudinaryStore cloudinaryStore = mock(CloudinaryStore.class);
    private final MembershipService membershipService = mock(MembershipService.class);
    private final SubmissionEventPublisher publisher = mock(SubmissionEventPublisher.class);
    private final NoOpTransactionManager transactionManager = new NoOpTransactionManager();
    private final TextCipher textCipher = new TextCipher(randomKey());
    private final SubmissionService service = new SubmissionService(submissions, fileValidator, textCipher,
            cloudinaryStore, membershipService, publisher, transactionManager, JsonMapper.builder().build());

    @BeforeEach
    void defaults() {
        when(submissions.findStoredRequest(any(), anyString())).thenReturn(Optional.empty());
        when(membershipService.findForUser(OWNER)).thenReturn(Optional.empty());
        when(cloudinaryStore.isConfigured()).thenReturn(true);
    }

    @Test
    void invalidPdf_cloudinaryNeverCalled() {
        when(fileValidator.validate(any(byte[].class)))
                .thenThrow(new UnsupportedMediaTypeException("UNSUPPORTED_FILE_TYPE", "Only PDF files are accepted."));

        assertThatThrownBy(() -> service.submit(OWNER, pdfRequest(KEY, TestPdfs.exeRenamed())))
                .isInstanceOf(UnsupportedMediaTypeException.class);

        verifyNoInteractions(cloudinaryStore);
        verify(submissions, never()).saveAndFlush(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void validPdf_validatedThenStoredPrivatelyThenSaved() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));

        UUID id = service.submit(OWNER, pdfRequest(KEY, pdf)).submissionId();

        Submission saved = savedSubmission();
        String publicId = CloudinaryStore.publicIdFor(OWNER, KEY, saved.getRequestSha256());
        InOrder order = inOrder(fileValidator, cloudinaryStore, submissions, publisher);
        order.verify(fileValidator).validate(pdf);
        order.verify(cloudinaryStore).upload(publicId, pdf);
        order.verify(submissions).saveAndFlush(any(Submission.class));
        order.verify(publisher).publish(any(SubmissionQueued.class));
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getSourceType()).isEqualTo(SourceType.PDF);
        assertThat(saved.getCloudinaryPublicId()).isEqualTo(publicId);
        assertThat(saved.getStatus()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(saved.getPublishAttempts()).isZero();
        assertThat(saved.getExpiresAt()).isEqualTo(saved.getLastPublishedAt().plus(Duration.ofDays(30)));
        assertThat(saved.getContentSha256()).isEqualTo(com.offershield.common.Hashing.sha256Hex(LETTER));
    }

    @Test
    void cloudinaryNotConfigured_pdfNotStored_submissionProceeds() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        when(cloudinaryStore.isConfigured()).thenReturn(false);

        try (LogCapture logs = LogCapture.of(SubmissionService.class)) {
            service.submit(OWNER, pdfRequest(KEY, pdf));

            assertThat(logs.events()).extracting(event -> event.getFormattedMessage())
                    .contains("pdf storage skipped: not configured");
        }
        verify(cloudinaryStore, never()).upload(anyString(), any());
        assertThat(savedSubmission().getCloudinaryPublicId()).isNull();
    }

    @Test
    void cloudinaryFails_503_nothingSaved() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        doThrow503().when(cloudinaryStore).upload(anyString(), any());

        assertThatThrownBy(() -> service.submit(OWNER, pdfRequest(KEY, pdf)))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(submissions, never()).saveAndFlush(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void text_encryptedWithSubmissionIdAsAad() {
        UUID id = service.submit(OWNER, textRequest(KEY, LETTER)).submissionId();

        Submission saved = savedSubmission();
        SealedText sealed = saved.getText();
        assertThat(new String(sealed.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Data Analyst");
        assertThat(saved.getKeyVersion()).isEqualTo(TextCipher.CURRENT_KEY_VERSION);
        assertThat(textCipher.decrypt(sealed, id.toString().getBytes(StandardCharsets.UTF_8))).isEqualTo(LETTER);
        assertThatThrownBy(() -> textCipher.decrypt(sealed, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(TextCipher.DecryptionFailedException.class);
        assertThat(saved.getContacts()).isNull();
        verifyNoInteractions(cloudinaryStore);
    }

    @Test
    void contacts_encryptedNotStoredInClear() {
        UUID id = service.submit(OWNER, new NewSubmission(KEY, null, LETTER, "Recruiter@Fictional-Jobs.Example",
                "+91 99999 01234", "test.marker@upi")).submissionId();

        Submission saved = savedSubmission();
        String raw = new String(saved.getContacts().bytes(), StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("99999").doesNotContain("test.marker").doesNotContain("Recruiter");
        String json = textCipher.decrypt(saved.getContacts(), (id + ":contacts").getBytes(StandardCharsets.UTF_8));
        assertThat(json).contains("+91 99999 01234").contains("test.marker@upi");
    }

    @Test
    void senderDomain_typedEmailWins_elseFirstEmailInLetter() {
        service.submit(OWNER, new NewSubmission(KEY, null, LETTER, "Recruiter@Fictional-Jobs.Example", null, null));
        assertThat(savedSubmission().getSenderDomain()).isEqualTo("fictional-jobs.example");

        SubmissionService fresh = newService();
        fresh.submit(OWNER, textRequest("another-key-0002", LETTER));
        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissions, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().get(1).getSenderDomain()).isEqualTo("fictional-careers.example");
    }

    @Test
    void noEmailAnywhere_senderDomainNull() {
        service.submit(OWNER, textRequest(KEY, "Dear Candidate, pay the registration fee of Rs 2,000 to confirm your seat."));

        assertThat(savedSubmission().getSenderDomain()).isNull();
    }

    @Test
    void approvedMember_onCampus_elseOffCampus() {
        UUID college = UUID.randomUUID();
        when(membershipService.findForUser(OWNER)).thenReturn(Optional.of(
                new MembershipResponse(UUID.randomUUID(), college, "Test College", "R1", "CSE", MembershipStatus.APPROVED, null, 1)));
        service.submit(OWNER, textRequest(KEY, LETTER));
        assertThat(savedSubmission().getCollegeId()).isEqualTo(college);

        when(membershipService.findForUser(OWNER)).thenReturn(Optional.of(
                new MembershipResponse(UUID.randomUUID(), college, "Test College", "R1", "CSE", MembershipStatus.PENDING, null, 0)));
        service.submit(OWNER, textRequest("another-key-0003", LETTER));
        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissions, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().get(1).getCollegeId()).isNull();
    }

    @Test
    void committed_publishedAfterCommit_outsideAnyTransaction() {
        AtomicBoolean insideTransaction = new AtomicBoolean(true);
        AtomicInteger commitsBeforePublish = new AtomicInteger(-1);
        doAnswer(invocation -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            commitsBeforePublish.set(transactionManager.commits());
            return true;
        }).when(publisher).publish(any(SubmissionQueued.class));

        UUID id = service.submit(OWNER, textRequest(KEY, LETTER)).submissionId();

        ArgumentCaptor<SubmissionQueued> event = ArgumentCaptor.forClass(SubmissionQueued.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().submissionId()).isEqualTo(id);
        assertThat(event.getValue().ownerUserId()).isEqualTo(OWNER);
        assertThat(event.getValue().sourceType()).isEqualTo(SourceType.TEXT);
        assertThat(insideTransaction).as("no transaction (and so no DB connection) is held while sending").isFalse();
        assertThat(commitsBeforePublish).as("the row was committed first").hasValue(1);
    }

    @Test
    void rolledBack_noSend() {
        when(submissions.saveAndFlush(any(Submission.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.submit(OWNER, textRequest(KEY, LETTER))).isInstanceOf(IllegalStateException.class);

        assertThat(transactionManager.rollbacks()).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    @Test
    void publishFails_uploadStillAccepted() {
        when(publisher.publish(any(SubmissionQueued.class))).thenReturn(false);

        assertThat(service.submit(OWNER, textRequest(KEY, LETTER)).submissionId()).isNotNull();
        verify(cloudinaryStore, never()).delete(anyString());
    }

    @Test
    void uploadLimitCheckedAgainInsideTheInsertTransaction() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        // 4 uploads when the request starts; a parallel request took the 5th slot while this PDF was being read.
        when(submissions.countByOwnerUserIdAndCreatedAtAfter(eq(OWNER), any())).thenReturn(4L, 5L);
        when(submissions.findOldestCreatedAtAfter(eq(OWNER), any())).thenReturn(Optional.of(Instant.now()));

        assertProblem(() -> service.submit(OWNER, pdfRequest(KEY, pdf)), HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_LIMIT");

        verify(submissions, never()).saveAndFlush(any());
        String uploaded = uploadedPublicId();
        verify(cloudinaryStore).delete(uploaded);
        verifyNoInteractions(publisher);
    }

    @Test
    void sameKeyDifferentPdfAtTheSameTime_losersPdfDeleted_winnersKept() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        String winnersHash = "f".repeat(64);
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(invocation -> {
            when(submissions.findStoredRequest(OWNER, KEY))
                    .thenReturn(Optional.of(new StoredRequest(UUID.randomUUID(), winnersHash)));
            throw new DataIntegrityViolationException("Duplicate entry");
        });

        assertProblem(() -> service.submit(OWNER, pdfRequest(KEY, pdf)), HttpStatus.UNPROCESSABLE_CONTENT,
                "IDEMPOTENCY_KEY_REUSED");

        String mine = uploadedPublicId();
        assertThat(mine).isNotEqualTo(CloudinaryStore.publicIdFor(OWNER, KEY, winnersHash));
        verify(cloudinaryStore).delete(mine);
        verify(cloudinaryStore, never()).delete(CloudinaryStore.publicIdFor(OWNER, KEY, winnersHash));
    }

    @Test
    void senderDomain_hostileText_fastAndNoStackOverflow() {
        String longRun = "a".repeat(50_000);
        String manyLabels = "x@" + "c.".repeat(20_000) + "in";

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(SubmissionService.senderDomain(null, longRun)).isNull();
            assertThat(SubmissionService.senderDomain(null, manyLabels)).as("longer than any real domain").isNull();
            assertThat(SubmissionService.senderDomain(null, longRun + " " + manyLabels
                    + " Write to hr.team@fictional-hiring.example.")).isEqualTo("fictional-hiring.example");
        });
    }

    @Test
    void senderDomain_skipsThingsThatAreNotEmailDomains() {
        assertThat(SubmissionService.senderDomain(null, "Pay to 99999@ybl or hr@intranet, then mail jobs@acme-careers.example"))
                .isEqualTo("acme-careers.example");
        assertThat(SubmissionService.senderDomain(null, "Contact hr@-bad-.example or hr@x.1")).isNull();
    }

    @Test
    void replaySameContent_sameIdWithoutRevalidationOrUpload() {
        UUID first = service.submit(OWNER, textRequest(KEY, LETTER)).submissionId();
        String requestHash = savedSubmission().getRequestSha256();
        when(submissions.findStoredRequest(OWNER, KEY)).thenReturn(Optional.of(new StoredRequest(first, requestHash)));

        UUID second = service.submit(OWNER, textRequest(KEY, LETTER)).submissionId();

        assertThat(second).isEqualTo(first);
        verify(submissions, org.mockito.Mockito.times(1)).saveAndFlush(any());
    }

    @Test
    void replayDifferentContent_422() {
        when(submissions.findStoredRequest(OWNER, KEY)).thenReturn(Optional.of(new StoredRequest(UUID.randomUUID(), "0".repeat(64))));

        assertProblem(() -> service.submit(OWNER, textRequest(KEY, LETTER)), HttpStatus.UNPROCESSABLE_CONTENT,
                "IDEMPOTENCY_KEY_REUSED");
        verify(submissions, never()).saveAndFlush(any());
    }

    @Test
    void sameTextDifferentPhone_isDifferentContent() {
        service.submit(OWNER, new NewSubmission(KEY, null, LETTER, null, "+91 99999 01111", null));
        String firstHash = savedSubmission().getRequestSha256();
        when(submissions.findStoredRequest(OWNER, KEY)).thenReturn(Optional.of(new StoredRequest(UUID.randomUUID(), firstHash)));

        assertProblem(() -> service.submit(OWNER, new NewSubmission(KEY, null, LETTER, null, "+91 99999 02222", null)),
                HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void duplicateKeyOnInsert_returnsTheWinnersId() {
        UUID winner = UUID.randomUUID();
        AtomicBoolean inserted = new AtomicBoolean();
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(invocation -> {
            Submission mine = invocation.getArgument(0);
            when(submissions.findStoredRequest(OWNER, KEY))
                    .thenReturn(Optional.of(new StoredRequest(winner, mine.getRequestSha256())));
            inserted.set(true);
            throw new DataIntegrityViolationException("Duplicate entry");
        });

        UUID id = service.submit(OWNER, textRequest(KEY, LETTER)).submissionId();

        assertThat(inserted).isTrue();
        assertThat(id).isEqualTo(winner);
        assertThat(transactionManager.rollbacks()).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    @Test
    void integrityErrorWithoutAWinningRow_uploadedPdfDeleted_errorRethrown() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        when(submissions.saveAndFlush(any(Submission.class))).thenThrow(new DataIntegrityViolationException("FK"));

        assertThatThrownBy(() -> service.submit(OWNER, pdfRequest(KEY, pdf)))
                .isInstanceOf(DataIntegrityViolationException.class);

        String uploaded = uploadedPublicId();
        verify(cloudinaryStore).delete(uploaded);
    }

    @Test
    void duplicateKeyWithStoredPdf_winnersPdfKept() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(invocation -> {
            Submission mine = invocation.getArgument(0);
            when(submissions.findStoredRequest(OWNER, KEY))
                    .thenReturn(Optional.of(new StoredRequest(UUID.randomUUID(), mine.getRequestSha256())));
            throw new DataIntegrityViolationException("Duplicate entry");
        });

        service.submit(OWNER, pdfRequest(KEY, pdf));

        verify(cloudinaryStore, never()).delete(anyString());
    }

    @Test
    void otherInsertFailure_uploadedPdfDeleted_errorRethrown() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        when(submissions.saveAndFlush(any(Submission.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.submit(OWNER, pdfRequest(KEY, pdf))).isInstanceOf(IllegalStateException.class);

        String uploaded = uploadedPublicId();
        verify(cloudinaryStore).delete(uploaded);
    }

    @Test
    void missingKey_400() {
        assertProblem(() -> service.submit(OWNER, textRequest(null, LETTER)), HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED");
        assertProblem(() -> service.submit(OWNER, textRequest("  ", LETTER)), HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void malformedKey_400() {
        assertProblem(() -> service.submit(OWNER, textRequest("short", LETTER)), HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY");
        assertProblem(() -> service.submit(OWNER, textRequest("has spaces in it", LETTER)), HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY");
        assertProblem(() -> service.submit(OWNER, textRequest("k".repeat(101), LETTER)), HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    void fileAndText_400_neither_400() {
        assertProblem(() -> service.submit(OWNER, new NewSubmission(KEY, pdfFile(TestPdfs.onePage()), LETTER, null, null, null)),
                HttpStatus.BAD_REQUEST, "FILE_OR_TEXT");
        assertProblem(() -> service.submit(OWNER, new NewSubmission(KEY, null, null, null, null, null)),
                HttpStatus.BAD_REQUEST, "FILE_OR_TEXT");
        assertProblem(() -> service.submit(OWNER, new NewSubmission(KEY, null, "   ", null, null, null)),
                HttpStatus.BAD_REQUEST, "FILE_OR_TEXT");
    }

    @Test
    void sixthUpload_429WithRetryAfter() {
        Instant oldest = Instant.now().minus(Duration.ofMinutes(3));
        when(submissions.countByOwnerUserIdAndCreatedAtAfter(eq(OWNER), any())).thenReturn(5L);
        when(submissions.findOldestCreatedAtAfter(eq(OWNER), any())).thenReturn(Optional.of(oldest));

        assertThatThrownBy(() -> service.submit(OWNER, textRequest(KEY, LETTER)))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("UPLOAD_LIMIT");
                    assertThat(ex.retryAfterSeconds()).isBetween(400L, 421L);
                });
        verify(submissions, never()).saveAndFlush(any());
    }

    @Test
    void replayIsNotRateLimited() {
        UUID first = UUID.randomUUID();
        service.submit(OWNER, textRequest(KEY, LETTER));
        String requestHash = savedSubmission().getRequestSha256();
        when(submissions.findStoredRequest(OWNER, KEY)).thenReturn(Optional.of(new StoredRequest(first, requestHash)));
        when(submissions.countByOwnerUserIdAndCreatedAtAfter(eq(OWNER), any())).thenReturn(5L);

        assertThat(service.submit(OWNER, textRequest(KEY, LETTER)).submissionId()).isEqualTo(first);
    }

    @Test
    void oversizeFile_413BeforeReadingOrHashing() {
        MockMultipartFile big = pdfFile(TestPdfs.pdfHeaderOfSize((int) FileValidator.MAX_BYTES + 1));
        org.mockito.Mockito.doCallRealMethod().when(fileValidator).checkSize(big);

        assertProblem(() -> service.submit(OWNER, new NewSubmission(KEY, big, null, null, null, null)),
                HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
        verify(submissions, never()).findStoredRequest(any(), anyString());
    }

    @Test
    void findContent_decryptsTextAndContacts() {
        UUID id = service.submit(OWNER, new NewSubmission(KEY, null, LETTER, "hr@fictional-jobs.example",
                "+91 99999 01234", "test.marker@upi")).submissionId();
        Submission saved = savedSubmission();
        when(submissions.findById(id)).thenReturn(Optional.of(saved));

        SubmissionContent content = service.findContent(id).orElseThrow();

        assertThat(content.text()).isEqualTo(LETTER);
        assertThat(content.senderEmail()).isEqualTo("hr@fictional-jobs.example");
        assertThat(content.senderDomain()).isEqualTo("fictional-jobs.example");
        assertThat(content.recruiterPhone()).isEqualTo("+91 99999 01234");
        assertThat(content.upiId()).isEqualTo("test.marker@upi");
        assertThat(content.toString()).doesNotContain("99999").doesNotContain("Data Analyst");
    }

    @Test
    void senderSource_typedEmail_typed() {
        UUID id = service.submit(OWNER, new NewSubmission(KEY, null, LETTER, "hr@fictional-jobs.example", null, null))
                .submissionId();
        Submission saved = savedSubmission();
        when(submissions.findById(id)).thenReturn(Optional.of(saved));

        assertThat(saved.getSenderSource()).isEqualTo(com.offershield.common.SenderSource.TYPED);
        assertThat(service.findContent(id).orElseThrow().senderSource())
                .isEqualTo(com.offershield.common.SenderSource.TYPED);
    }

    @Test
    void senderSource_blankSenderEmail_letter() {
        // P13 (S2): the domain then comes from the letter itself (D-33.6), which its author controls.
        UUID id = service.submit(OWNER, textRequest(KEY, LETTER)).submissionId();
        Submission saved = savedSubmission();
        when(submissions.findById(id)).thenReturn(Optional.of(saved));

        assertThat(saved.getSenderDomain()).isEqualTo("fictional-careers.example");
        assertThat(saved.getSenderSource()).isEqualTo(com.offershield.common.SenderSource.LETTER);
        assertThat(service.findContent(id).orElseThrow().senderSource())
                .isEqualTo(com.offershield.common.SenderSource.LETTER);
    }

    @Test
    void senderSource_noEmailAnywhere_null() {
        service.submit(OWNER, textRequest(KEY, "Dear Candidate, you are selected. Call the office to join."));

        assertThat(savedSubmission().getSenderSource()).isNull();
    }

    @Test
    void sameKeyDoubleClickAtTheFifthUpload_replaysTheWinner_keepsItsPdf() {
        // P13 (CR2): clicks A and B send the same letter with the same key while the user has 4 uploads. B passed the
        // early checks before A committed the 5th; B's insert transaction must answer with A's submission, not 429,
        // and must not delete the PDF (both requests share one public ID).
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        service.submit(OWNER, pdfRequest(KEY, pdf));
        String requestHash = savedSubmission().getRequestSha256();
        UUID winner = UUID.randomUUID();
        when(submissions.findStoredRequest(OWNER, KEY))
                .thenReturn(Optional.empty(), Optional.of(new StoredRequest(winner, requestHash)));
        when(submissions.countByOwnerUserIdAndCreatedAtAfter(eq(OWNER), any())).thenReturn(4L, 5L);
        when(submissions.findOldestCreatedAtAfter(eq(OWNER), any())).thenReturn(Optional.of(Instant.now()));

        UUID id = service.submit(OWNER, pdfRequest(KEY, pdf)).submissionId();

        assertThat(id).isEqualTo(winner);
        verify(cloudinaryStore, never()).delete(anyString());
        verify(submissions, times(1)).saveAndFlush(any());
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void parallelUploadsAtTheLimit_onlyOneSaved() throws Exception {
        // P13 (S3): 4 uploads so far; two new letters at once. Without a per-user lock both count 4 and both insert.
        AtomicInteger rows = new AtomicInteger(4);
        when(submissions.countByOwnerUserIdAndCreatedAtAfter(eq(OWNER), any())).thenAnswer(invocation -> (long) rows.get());
        when(submissions.findOldestCreatedAtAfter(eq(OWNER), any())).thenReturn(Optional.of(Instant.now()));
        CyclicBarrier bothInserting = new CyclicBarrier(2);
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(invocation -> {
            try {
                bothInserting.await(1, TimeUnit.SECONDS);
            } catch (BrokenBarrierException | TimeoutException alone) {
                // The other request is not inserting at the same time.
            }
            rows.incrementAndGet();
            return invocation.getArgument(0);
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = "parallel-key-000" + i;
                String letter = LETTER + " Copy " + i + ".";
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.submit(OWNER, textRequest(key, letter));
                        return "accepted";
                    } catch (ApiException ex) {
                        return ex.errorCode();
                    }
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                outcomes.add(result.get(20, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsExactlyInAnyOrder("accepted", "UPLOAD_LIMIT");
        assertThat(rows).hasValue(5);
    }

    @Test
    void secondPdfWhileTheFirstIsBeingRead_409UploadInProgress() throws Exception {
        // P13 (S1): one PDF read at a time per user, so one account cannot keep both reader threads busy.
        byte[] first = TestPdfs.onePage();
        byte[] second = TestPdfs.withPages(2);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fileValidator.validate(first)).thenAnswer(invocation -> {
            reading.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new ValidatedPdf(first, LETTER, 1);
        });
        when(fileValidator.validate(second)).thenReturn(new ValidatedPdf(second, LETTER, 2));
        UUID otherUser = UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SubmissionAccepted> firstUpload = pool.submit(() -> service.submit(OWNER, pdfRequest(KEY, first)));
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.submit(OWNER, pdfRequest("another-key-0004", second)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.errorCode()).isEqualTo("UPLOAD_IN_PROGRESS");
                        assertThat(ex.getMessage()).isEqualTo("Another letter is still being read. Wait for it to finish.");
                    });
            assertThat(service.submit(otherUser, pdfRequest("another-key-0005", second)).submissionId())
                    .as("another user's letter is read meanwhile").isNotNull();

            release.countDown();
            assertThat(firstUpload.get(10, TimeUnit.SECONDS).submissionId()).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(service.submit(OWNER, pdfRequest("another-key-0006", second)).submissionId())
                .as("once the first letter is read, the next one is").isNotNull();
    }

    @Test
    void tenUnreadablePdfs_eleventh429UploadAttemptLimit_withoutReadingIt() {
        // P13 (S1): files that fail to read count per user (10 per 10 minutes), so rejected files cannot keep the
        // reader busy; they create no submission row, so the D-12 count never sees them.
        when(fileValidator.validate(any(byte[].class)))
                .thenThrow(new UnprocessableException("PDF_UNREADABLE", "We couldn't read this PDF."));
        for (int i = 0; i < 10; i++) {
            String key = "unreadable-key-" + i;
            assertProblem(() -> service.submit(OWNER, pdfRequest(key, TestPdfs.corrupt())),
                    HttpStatus.UNPROCESSABLE_CONTENT, "PDF_UNREADABLE");
        }

        assertThatThrownBy(() -> service.submit(OWNER, pdfRequest("unreadable-key-10", TestPdfs.corrupt())))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("UPLOAD_ATTEMPT_LIMIT");
                    assertThat(ex.getMessage())
                            .isEqualTo("Too many letters that couldn't be read. Try again in a few minutes.");
                    assertThat(ex.retryAfterSeconds()).isBetween(1L, 600L);
                });
        verify(fileValidator, times(10)).validate(any(byte[].class));
        assertThat(service.submit(OWNER, textRequest("pasted-key-0011", LETTER)).submissionId())
                .as("pasted text is not read by the PDF reader").isNotNull();
    }

    @Test
    void busyReaderOrReadableFiles_notCountedAsFailedReads() {
        byte[] pdf = TestPdfs.onePage();
        when(fileValidator.validate(any(byte[].class)))
                .thenThrow(new ServiceUnavailableException("SERVER_BUSY", "Too many files are being read right now."));
        for (int i = 0; i < 12; i++) {
            String key = "busy-key-" + i;
            assertProblem(() -> service.submit(OWNER, pdfRequest(key, pdf)), HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVER_BUSY");
        }
        org.mockito.Mockito.reset(fileValidator);
        when(fileValidator.validate(pdf)).thenReturn(new ValidatedPdf(pdf, LETTER, 1));
        for (int i = 0; i < 12; i++) {
            assertThat(service.submit(OWNER, pdfRequest("readable-key-" + i, pdf)).submissionId()).isNotNull();
        }
    }

    private String uploadedPublicId() {
        ArgumentCaptor<String> publicId = ArgumentCaptor.forClass(String.class);
        verify(cloudinaryStore).upload(publicId.capture(), any());
        return publicId.getValue();
    }

    private Submission savedSubmission() {
        ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
        verify(submissions, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private SubmissionService newService() {
        return new SubmissionService(submissions, fileValidator, textCipher, cloudinaryStore, membershipService, publisher,
                transactionManager, JsonMapper.builder().build());
    }

    private static org.mockito.stubbing.Stubber doThrow503() {
        return org.mockito.Mockito.doThrow(new ServiceUnavailableException("STORAGE_UNAVAILABLE", "Upload failed."));
    }

    private static NewSubmission textRequest(String key, String text) {
        return new NewSubmission(key, null, text, null, null, null);
    }

    private static NewSubmission pdfRequest(String key, byte[] pdf) {
        return new NewSubmission(key, pdfFile(pdf), null, null, null, null);
    }

    private static MockMultipartFile pdfFile(byte[] pdf) {
        return new MockMultipartFile("file", "offer.pdf", "application/pdf", pdf);
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
                                      String errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(status);
            assertThat(ex.errorCode()).isEqualTo(errorCode);
        });
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
