package com.offershield.retention;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.offershield.ai.LlmCache;
import com.offershield.analysis.ReportService;
import com.offershield.auth.Role;
import com.offershield.common.Ids;
import com.offershield.common.kafka.ProcessedEvents;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.review.ReviewDecision;
import com.offershield.review.ReviewDecisionRequest;
import com.offershield.review.ReviewService;
import com.offershield.scamreport.CreateScamReportRequest;
import com.offershield.scamreport.ScamReportDecision;
import com.offershield.scamreport.ScamReportService;
import com.offershield.submission.CloudinaryStore;
import com.offershield.submission.SubmissionRetention;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P11-01 on MySQL 8.4 (D-06; PRD F10, §6.3, §9.1, §14.6): 30 days after upload the daily job deletes the private PDF,
 * wipes the submission (text, contacts, sender domain; status DELETED), deletes its report and signals and its report
 * snapshot, unless an OPEN review case or scam report still needs the evidence (then 30 days after the decision);
 * scam-report descriptions go 30 days after the decision; AI-cache rows go after 30 days and {@code processed_event}
 * rows after 14. Hashed indicators and the audit log stay. A failed Cloudinary delete is retried on the next run, a
 * second run changes nothing, and the log carries counts only. Most runs use a job built with a mocked file store
 * (nothing reaches Cloudinary); one uses the application's own bean, whose storage is not configured in tests.
 * Every row is fake and made by this test; rows are expired relative to the run's clock.
 */
class RetentionJobTest extends IntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DESCRIPTION = "They asked me to pay a deposit to RETENTION-DESCRIPTION-MARKER today.";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private RetentionService applicationRetention;
    @Autowired
    private ReportService reports;
    @Autowired
    private ReportSnapshotService snapshots;
    @Autowired
    private ReviewService reviews;
    @Autowired
    private ScamReportService scamReports;
    @Autowired
    private LlmCache llmCache;
    @Autowired
    private ProcessedEvents processedEvents;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final CloudinaryStore store = mock(CloudinaryStore.class);
    private CaseFixtures fixtures;
    private RetentionService retention;
    private Instant now;

    @BeforeEach
    void setUp() {
        fixtures = new CaseFixtures(context, mockMvc);
        when(store.isConfigured()).thenReturn(true);
        retention = new RetentionService(new SubmissionRetention(namedJdbc, store), reports, snapshots, reviews,
                scamReports, llmCache, processedEvents, transactionManager);
        now = Instant.now();
    }

    @Test
    void expired_allCleaned() throws Exception {
        UUID owner = fixtures.user();
        UUID admin = fixtures.admin();
        String domain = CaseFixtures.domain();
        UUID letter = analysedLetter(owner, null, domain);
        UUID scamReport = scamReports.report(owner, new CreateScamReportRequest(letter, DESCRIPTION, 5000)).id();
        scamReports.decide(admin, scamReport, 0, ScamReportDecision.CONFIRM);
        String publicId = storedPdf(letter);
        expire(letter);
        decidedDaysAgo("scam_report", scamReport, 31);
        String oldCache = cacheRow(31);
        String oldEvent = processedEvent(15);
        AtomicBoolean deletedInTransaction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            deletedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(store).delete(publicId);

        RetentionResult result = retention.run(now).orElseThrow();

        verify(store).delete(publicId);
        assertThat(deletedInTransaction).as("Cloudinary is called outside any database transaction").isFalse();
        Map<String, Object> row = submissionRow(letter);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("text_cipher")).isNull();
        assertThat(row.get("contacts_cipher")).isNull();
        assertThat(row.get("sender_domain")).isNull();
        assertThat(row.get("cloudinary_public_id")).isNull();
        assertThat(count("SELECT COUNT(*) FROM report WHERE id = ?", letter)).isZero();
        assertThat(count("SELECT COUNT(*) FROM report_signal WHERE report_id = ?", letter)).isZero();
        assertThat(count("SELECT COUNT(*) FROM report_snapshot WHERE submission_id = ?", letter)).isZero();
        Map<String, Object> reportRow = jdbc.queryForMap("SELECT status, description_cipher FROM scam_report "
                + "WHERE id = ?", Ids.toBytes(scamReport));
        assertThat(reportRow.get("status")).isEqualTo("CONFIRMED");
        assertThat(reportRow.get("description_cipher")).isNull();
        // Hashed indicators and the audit log are kept (PRD §14.6, D-06).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM indicator WHERE type = 'DOMAIN' AND match_key = ? "
                + "AND status = 'CONFIRMED'", Integer.class, domain)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM indicator_reporter WHERE submission_id = ?", letter)).isPositive();
        assertThat(fixtures.auditRows(admin, "SCAM_REPORT_CONFIRMED", scamReport)).isEqualTo(1);
        assertThat(cacheRowExists(oldCache)).isFalse();
        assertThat(processedEventExists(oldEvent)).isFalse();
        mockMvc.perform(get("/api/submissions/{id}", letter).with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.pdfStored").value(false));
        mockMvc.perform(get("/api/reports/{id}", letter).with(signedIn(owner, Role.USER)))
                .andExpect(status().isNotFound());
        assertThat(result.submissionsWiped()).isPositive();
        assertThat(result.reportsDeleted()).isPositive();
        assertThat(result.pdfsDeleted()).isPositive();
        assertThat(result.snapshotsDeleted()).isPositive();
        assertThat(result.descriptionsWiped()).isPositive();
        assertThat(result.llmCacheDeleted()).isPositive();
        assertThat(result.processedEventsDeleted()).isPositive();
        assertThat(result.failedSteps()).isZero();
    }

    @Test
    void notExpired_untouched() throws Exception {
        UUID owner = fixtures.user();
        String domain = CaseFixtures.domain();
        UUID letter = analysedLetter(owner, null, domain);
        String publicId = storedPdf(letter);
        String freshCache = cacheRow(29);
        String freshEvent = processedEvent(13);

        retention.run(now).orElseThrow();

        verify(store, never()).delete(publicId);
        Map<String, Object> row = submissionRow(letter);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("text_cipher")).isNotNull();
        assertThat(row.get("contacts_cipher")).isNotNull();
        assertThat(row.get("sender_domain")).isEqualTo(domain);
        assertThat(row.get("cloudinary_public_id")).isEqualTo(publicId);
        assertThat(count("SELECT COUNT(*) FROM report WHERE id = ?", letter)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM report_signal WHERE report_id = ?", letter)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM report_snapshot WHERE submission_id = ?", letter)).isEqualTo(1);
        assertThat(cacheRowExists(freshCache)).isTrue();
        assertThat(processedEventExists(freshEvent)).isTrue();
    }

    @Test
    void snapshotKeptWhileCaseOpen() throws Exception {
        UUID college = fixtures.college();
        UUID tpo = fixtures.tpo(college);
        UUID student = fixtures.user();
        fixtures.approve(student, college);
        UUID letter = analysedLetter(student, college, CaseFixtures.domain());
        UUID caseId = reviews.request(student, letter).id();
        expire(letter);

        RetentionResult first = retention.run(now).orElseThrow();

        assertThat(submissionRow(letter).get("status")).isEqualTo("DELETED");
        assertThat(count("SELECT COUNT(*) FROM report WHERE id = ?", letter)).isZero();
        assertThat(snapshotExists(letter)).as("the OPEN case still needs the evidence").isTrue();
        assertThat(reviews.get(tpo, caseId).evidence()).isNotNull();
        assertThat(first.snapshotsKept()).isPositive();

        // Decided now: the evidence stays for another 30 days.
        reviews.decide(tpo, caseId, 0, new ReviewDecisionRequest(ReviewDecision.INCONCLUSIVE, null));
        retention.run(now).orElseThrow();
        assertThat(snapshotExists(letter)).isTrue();
        decidedDaysAgo("review_case", caseId, 29);
        retention.run(now).orElseThrow();
        assertThat(snapshotExists(letter)).isTrue();

        decidedDaysAgo("review_case", caseId, 31);
        RetentionResult last = retention.run(now).orElseThrow();

        assertThat(snapshotExists(letter)).isFalse();
        assertThat(last.snapshotsDeleted()).isPositive();
        assertThat(reviews.get(tpo, caseId).evidence()).as("P10 shows a deleted snapshot as no evidence").isNull();
        assertThat(reviews.get(tpo, caseId).status().name()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    void snapshotKeptWhileScamReportOpen() throws Exception {
        UUID owner = fixtures.user();
        UUID admin = fixtures.admin();
        String domain = CaseFixtures.domain();
        UUID letter = analysedLetter(owner, null, domain);
        UUID scamReport = scamReports.report(owner, new CreateScamReportRequest(letter, DESCRIPTION, null)).id();
        expire(letter);

        retention.run(now).orElseThrow();

        assertThat(submissionRow(letter).get("status")).isEqualTo("DELETED");
        assertThat(snapshotExists(letter)).as("the OPEN scam report still needs the evidence").isTrue();
        assertThat(descriptionCipher(scamReport)).as("the admin still needs the description").isNotNull();
        // So the admin can still confirm it after day 30; both stay for 30 days after that decision.
        scamReports.decide(admin, scamReport, 0, ScamReportDecision.CONFIRM);
        decidedDaysAgo("scam_report", scamReport, 29);
        retention.run(now).orElseThrow();
        assertThat(snapshotExists(letter)).isTrue();
        assertThat(descriptionCipher(scamReport)).isNotNull();
        decidedDaysAgo("scam_report", scamReport, 31);

        retention.run(now).orElseThrow();

        assertThat(snapshotExists(letter)).isFalse();
        assertThat(descriptionCipher(scamReport)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM indicator WHERE type = 'DOMAIN' AND match_key = ? "
                + "AND status = 'CONFIRMED'", Integer.class, domain)).isEqualTo(1);
    }

    @Test
    void cloudinaryFails_retriedNextRun() throws Exception {
        UUID owner = fixtures.user();
        UUID letter = analysedLetter(owner, null, CaseFixtures.domain());
        String publicId = storedPdf(letter);
        expire(letter);
        doThrow(new IllegalStateException("pdf delete failed: SocketTimeoutException")).when(store).delete(publicId);

        RetentionResult first = retention.run(now).orElseThrow();

        Map<String, Object> row = submissionRow(letter);
        assertThat(row.get("status")).as("the letter text is still wiped on time").isEqualTo("DELETED");
        assertThat(row.get("text_cipher")).isNull();
        assertThat(row.get("cloudinary_public_id")).as("kept until the delete succeeds").isEqualTo(publicId);
        assertThat(first.pdfDeleteFailures()).isPositive();
        mockMvc.perform(get("/api/submissions/{id}", letter).with(signedIn(owner, Role.USER)))
                .andExpect(jsonPath("$.pdfStored").value(false));

        doNothing().when(store).delete(publicId);
        RetentionResult second = retention.run(now).orElseThrow();

        assertThat(submissionRow(letter).get("cloudinary_public_id")).isNull();
        assertThat(second.pdfsDeleted()).isPositive();
        retention.run(now).orElseThrow();
        verify(store, times(2)).delete(publicId);
    }

    @Test
    void secondRun_doesNothing() throws Exception {
        UUID owner = fixtures.user();
        UUID admin = fixtures.admin();
        UUID letter = analysedLetter(owner, null, CaseFixtures.domain());
        UUID scamReport = scamReports.report(owner, new CreateScamReportRequest(letter, DESCRIPTION, null)).id();
        scamReports.decide(admin, scamReport, 0, ScamReportDecision.REJECT);
        storedPdf(letter);
        expire(letter);
        decidedDaysAgo("scam_report", scamReport, 31);
        cacheRow(40);
        processedEvent(20);
        retention.run(now).orElseThrow();
        clearInvocations(store);

        RetentionResult second = retention.run(now).orElseThrow();

        verify(store, never()).delete(anyString());
        assertThat(second.submissionsWiped()).isZero();
        assertThat(second.reportsDeleted()).isZero();
        assertThat(second.pdfsDeleted()).isZero();
        assertThat(second.pdfDeleteFailures()).isZero();
        assertThat(second.snapshotsDeleted()).isZero();
        assertThat(second.descriptionsWiped()).isZero();
        assertThat(second.llmCacheDeleted()).isZero();
        assertThat(second.processedEventsDeleted()).isZero();
        assertThat(second.failedSteps()).isZero();
        assertThat(submissionRow(letter).get("status")).isEqualTo("DELETED");
    }

    @Test
    void storageNotConfigured_pdfKeptForNextRun_noError() throws Exception {
        UUID owner = fixtures.user();
        UUID letter = analysedLetter(owner, null, CaseFixtures.domain());
        String publicId = storedPdf(letter);
        expire(letter);
        try (LogCapture logs = LogCapture.of(SubmissionRetention.class)) {

            RetentionResult result = applicationRetention.run(now).orElseThrow();

            assertThat(result.failedSteps()).isZero();
            assertThat(result.pdfDeleteFailures()).isPositive();
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("pdf delete skipped: storage not configured");
        } finally {
            // This test's PDF can never be deleted (storage is off): don't leave it for the other tests' runs.
            jdbc.update("UPDATE submission SET cloudinary_public_id = NULL WHERE id = ?", Ids.toBytes(letter));
        }
        Map<String, Object> row = submissionRow(letter);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("text_cipher")).isNull();
        assertThat(publicId).isNotBlank();
    }

    @Test
    void log_countsAndIdsOnly() throws Exception {
        UUID owner = fixtures.user();
        String domain = CaseFixtures.domain();
        UUID letter = analysedLetter(owner, null, domain);
        UUID scamReport = scamReports.report(owner, new CreateScamReportRequest(letter, DESCRIPTION, null)).id();
        scamReports.decide(fixtures.admin(), scamReport, 0, ScamReportDecision.REJECT);
        String publicId = storedPdf(letter);
        expire(letter);
        decidedDaysAgo("scam_report", scamReport, 31);
        doThrow(new IllegalStateException("pdf delete failed: SocketTimeoutException")).when(store).delete(publicId);

        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.root()) {
            retention.run(now).orElseThrow();
            doNothing().when(store).delete(publicId);
            retention.run(now).orElseThrow();
            events = logs.events();
        }

        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.matches("retention run: submissionsWiped=\\d+ reportsDeleted=\\d+ "
                        + "pdfsDeleted=\\d+ pdfDeleteFailures=\\d+ snapshotsDeleted=\\d+ snapshotsKept=\\d+ "
                        + "descriptionsWiped=\\d+ llmCacheDeleted=\\d+ processedEventsDeleted=\\d+ failedSteps=\\d+ "
                        + "failedBatches=\\d+ durationMs=\\d+"));
        assertThat(events).filteredOn(event -> letter.toString().equals(event.getMDCPropertyMap().get("submissionId")))
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.startsWith("pdf delete failed"));
        String email = fixtures.email(owner);
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage()).doesNotContain(publicId, domain, email,
                    "RETENTION-DESCRIPTION-MARKER", "fictional letter");
            assertThat(event.getMDCPropertyMap().values()).doesNotContain(publicId, domain, email);
        }
    }

    /** A real pasted-text submission with a snapshot, made to look analysed: report, signal, contacts, domain. */
    private UUID analysedLetter(UUID owner, UUID college, String domain) throws Exception {
        UUID letter = fixtures.analysed(owner, college, domain, List.of(CaseFixtures.key()), CaseFixtures.simhash());
        byte[] fakeContactsCipher = new byte[64];
        RANDOM.nextBytes(fakeContactsCipher);
        jdbc.update("UPDATE submission SET status = 'READY', contacts_cipher = ?, sender_domain = ? WHERE id = ?",
                fakeContactsCipher, domain, Ids.toBytes(letter));
        jdbc.update("INSERT INTO report (id, owner_user_id, college_id, score, band, hard_override, partial, ai_used, "
                        + "explanation, sender_domain) VALUES (?, ?, ?, 30, 'MEDIUM', FALSE, FALSE, FALSE, "
                        + "'A fictional explanation.', ?)", Ids.toBytes(letter), Ids.toBytes(owner),
                college == null ? null : Ids.toBytes(college), domain);
        jdbc.update("INSERT INTO report_signal (id, report_id, check_code, outcome, points, hard_override, explanation, "
                        + "evidence, source_kind) VALUES (?, ?, 'C1', 'TRIGGERED', 0, TRUE, "
                        + "'The letter asks you to pay money.', 'pay Rs. 5,000 refundable deposit', 1)",
                Ids.toBytes(Ids.newId()), Ids.toBytes(letter));
        return letter;
    }

    private String storedPdf(UUID letter) {
        String publicId = "submissions/" + randomHex(32);
        fixtures.storedPdf(letter, publicId);
        return publicId;
    }

    /** Uploaded 31 days before the run, so its 30 days ended yesterday. */
    private void expire(UUID letter) {
        jdbc.update("UPDATE submission SET created_at = ?, expires_at = ? WHERE id = ?",
                utc(now.minus(Duration.ofDays(31))), utc(now.minus(Duration.ofDays(1))), Ids.toBytes(letter));
    }

    private void decidedDaysAgo(String table, UUID id, int days) {
        jdbc.update("UPDATE " + table + " SET decided_at = ? WHERE id = ?", utc(now.minus(Duration.ofDays(days))),
                Ids.toBytes(id));
    }

    private String cacheRow(int daysOld) {
        String key = randomHex(32);
        jdbc.update("INSERT INTO llm_cache (cache_key, task, response_json, provider, model, created_at) "
                + "VALUES (?, 'extract', '{}', 'groq', 'test-model', ?)", key, utc(now.minus(Duration.ofDays(daysOld))));
        return key;
    }

    private String processedEvent(int daysOld) {
        String eventId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO processed_event (consumer, event_id, processed_at) VALUES ('retention-test', ?, ?)",
                eventId, utc(now.minus(Duration.ofDays(daysOld))));
        return eventId;
    }

    private boolean cacheRowExists(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM llm_cache WHERE cache_key = ?", Integer.class, key) > 0;
    }

    private boolean processedEventExists(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = 'retention-test' "
                + "AND event_id = ?", Integer.class, eventId) > 0;
    }

    private boolean snapshotExists(UUID letter) {
        return count("SELECT COUNT(*) FROM report_snapshot WHERE submission_id = ?", letter) > 0;
    }

    private byte[] descriptionCipher(UUID scamReport) {
        return jdbc.queryForObject("SELECT description_cipher FROM scam_report WHERE id = ?", byte[].class,
                Ids.toBytes(scamReport));
    }

    private Map<String, Object> submissionRow(UUID letter) {
        return jdbc.queryForMap("SELECT status, text_cipher, contacts_cipher, sender_domain, cloudinary_public_id "
                + "FROM submission WHERE id = ?", Ids.toBytes(letter));
    }

    private int count(String sql, UUID id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, Ids.toBytes(id));
        return count == null ? 0 : count;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
