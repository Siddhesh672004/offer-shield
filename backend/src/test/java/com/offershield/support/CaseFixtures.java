package com.offershield.support;

import com.jayway.jsonpath.JsonPath;
import com.offershield.analysis.NewReport;
import com.offershield.analysis.ReportService;
import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.Score;
import com.offershield.auth.AppUser;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.College;
import com.offershield.college.CollegeRepository;
import com.offershield.common.Ids;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.registry.ReportSnapshotService;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fake users, colleges, memberships, submissions and report snapshots for the P10 scam-report, review and PDF-link
 * tests. Every name, address, key and domain is random and made up (the test database is shared by every test of the
 * JVM). Roles are written to the database, because the session role is checked against it on every request.
 */
public final class CaseFixtures {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTER = "Dear Candidate, this is a fictional letter used only by the P10 tests.";

    private final OidcUserProvisioningService provisioning;
    private final AppUserRepository users;
    private final CollegeRepository colleges;
    private final ReportSnapshotService snapshots;
    private final ReportService reports;
    private final JdbcTemplate jdbc;
    private final MockMvc mockMvc;

    public CaseFixtures(ApplicationContext context, MockMvc mockMvc) {
        this.provisioning = context.getBean(OidcUserProvisioningService.class);
        this.users = context.getBean(AppUserRepository.class);
        this.colleges = context.getBean(CollegeRepository.class);
        this.snapshots = context.getBean(ReportSnapshotService.class);
        this.reports = context.getBean(ReportService.class);
        this.jdbc = context.getBean(JdbcTemplate.class);
        this.mockMvc = mockMvc;
    }

    public UUID user() {
        String key = UUID.randomUUID().toString();
        return provisioning.provision(new OidcIdentity("sub-p10-" + key, "user-p10-" + key + "@example.com", true,
                "Test User " + key.substring(0, 8))).userId();
    }

    public UUID admin() {
        return withRole(user(), Role.ADMIN, null);
    }

    public UUID college() {
        return colleges.saveAndFlush(College.create("P10 College " + UUID.randomUUID(), "Pune")).getId();
    }

    /** A TPO of {@code collegeId}. */
    public UUID tpo(UUID collegeId) {
        return withRole(user(), Role.TPO, collegeId);
    }

    /** An APPROVED membership written directly (no decision email). */
    public void approve(UUID studentId, UUID collegeId) {
        jdbc.update("INSERT INTO membership (id, user_id, college_id, roll_no, department, status) "
                        + "VALUES (?, ?, ?, ?, 'Computer Engineering', 'APPROVED')",
                Ids.toBytes(Ids.newId()), Ids.toBytes(studentId), Ids.toBytes(collegeId),
                "P10-" + RANDOM.nextInt(1_000_000));
    }

    /** A real (QUEUED) pasted-text submission of {@code owner}. */
    public UUID submission(UUID owner) throws Exception {
        String body = mockMvc.perform(multipart("/api/submissions")
                        .param("text", LETTER)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(signedIn(owner, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }

    /** A submission of {@code owner} with a stored report snapshot carrying these markers. */
    public UUID analysed(UUID owner, UUID collegeId, String senderDomain, List<String> phoneHmacs, Long simhash)
            throws Exception {
        UUID submission = submission(owner);
        snapshot(submission, owner, collegeId, senderDomain, phoneHmacs, simhash);
        return submission;
    }

    /**
     * P13: a submission of {@code owner} whose report is saved (the analysis finished) but whose registry snapshot is
     * not stored yet (the {@code registry-snapshot} consumer lags or failed).
     */
    public UUID analysedWithoutSnapshot(UUID owner, UUID collegeId) throws Exception {
        UUID submission = submission(owner);
        reports.save(UUID.randomUUID(), new NewReport(submission, owner, collegeId, null,
                new Score(30, Band.HIGH, true), false, List.of(CheckResult.override(CheckCode.C1,
                "The letter asks you to pay money.", "pay Rs. 5,000 refundable deposit", 1))));
        return submission;
    }

    public void snapshot(UUID submission, UUID owner, UUID collegeId, String senderDomain, List<String> phoneHmacs,
                         Long simhash) {
        snapshots.store(UUID.randomUUID(), new AnalysisCompletedEvent.Payload(submission, owner, collegeId,
                AnalysisCompletedEvent.READY, 30, "HIGH", true, List.of(), List.of(
                new AnalysisCompletedEvent.Signal("C1", "TRIGGERED", 0, true, "The letter asks you to pay money.",
                        "pay Rs. 5,000 refundable deposit", 1, false)),
                senderDomain, phoneHmacs, List.of(), simhash, true), Instant.now());
    }


    /** A snapshot of {@code submission} with one phone key and its P13 (N2) source and masked hint. */
    public void snapshotWithHint(UUID submission, UUID owner, String phoneKey, String source, String hint) {
        snapshots.store(UUID.randomUUID(), new AnalysisCompletedEvent.Payload(submission, owner, null,
                AnalysisCompletedEvent.READY, 30, "HIGH", true, List.of(), List.of(), null, List.of(phoneKey),
                List.of(), null, true, null, 0,
                List.of(new AnalysisCompletedEvent.MarkerHint("PHONE", phoneKey, source, hint))), Instant.now());
    }
    /** Marks the submission's PDF as kept privately under {@code publicId} (test setup only). */
    public void storedPdf(UUID submission, String publicId) {
        jdbc.update("UPDATE submission SET cloudinary_public_id = ? WHERE id = ?", publicId, Ids.toBytes(submission));
    }

    public String email(UUID userId) {
        return users.findById(userId).orElseThrow().getEmail();
    }

    public int auditRows(UUID actor, String action, UUID target) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id = ? AND action = ? "
                + "AND target_id = ?", Integer.class, Ids.toBytes(actor), action, Ids.toBytes(target));
        return count == null ? 0 : count;
    }

    public int auditRowsFor(UUID target) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id = ?", Integer.class,
                Ids.toBytes(target));
        return count == null ? 0 : count;
    }

    /** A random 64-hex-digit key, standing in for the HMAC of a fake phone number. */
    public static String key() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A random registrable {@code .com} domain (snapshots never look it up). */
    public static String domain() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "scam-p10-" + HexFormat.of().formatHex(bytes) + ".com";
    }

    public static long simhash() {
        return RANDOM.nextLong() | Long.MIN_VALUE;
    }

    private UUID withRole(UUID userId, Role role, UUID collegeId) {
        AppUser user = users.findById(userId).orElseThrow();
        if (collegeId != null) {
            user.assignTpoCollege(collegeId);
        }
        user.changeRole(role);
        users.saveAndFlush(user);
        return userId;
    }
}
