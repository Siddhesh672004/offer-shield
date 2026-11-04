package com.offershield.analysis;

import com.jayway.jsonpath.JsonPath;
import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.Score;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.support.AiStubs;
import com.offershield.support.GroqStub.Reply;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P7-03 (PRD §4 F4, §11): {@code POST /api/reports/{id}/explain}. Owner only (others and unknown IDs → the same 404,
 * no AI call), XSRF required; an AI-written explanation is saved in {@code report.explanation} and reused; without
 * AI the answer is a template built from the signals, not saved. The AI is the local Groq stub. Fake data only.
 */
class ExplainServiceTest extends IntegrationTest {

    private static final String LETTER_MARKER = "LETTER-ONLY-MARKER-P7";
    private static final String SUMMARY_TEXT = "This letter shows strong scam signs: it asks you to pay a refundable "
            + "deposit and pushes you to decide fast. One check could not run. Confirm the offer through the company's "
            + "official website or careers page, and never pay money to get a job.";
    private static final String SUMMARY = "{\"summary\":\"" + SUMMARY_TEXT + "\"}";

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private ReportService reports;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID owner;
    private UUID stranger;
    private UUID submission;

    @BeforeEach
    void setUp() throws Exception {
        owner = user();
        stranger = user();
        submission = upload(owner);
        reports.save(UUID.randomUUID(), new NewReport(submission, owner, null, "young-firm.com",
                new Score(10, Band.HIGH, true), true, List.of(
                CheckResult.override(CheckCode.C1, "The letter asks you to pay money.", "Pay ₹5,000 refundable deposit", 1),
                CheckResult.unavailable(CheckCode.C5, "The registry gave no date.", 2),
                CheckResult.triggered(CheckCode.C9, 10, "Pressure language.", "“within 24 hours”", 1))));
        AiStubs.GROQ.reset();
    }

    @AfterEach
    void resetStub() {
        AiStubs.GROQ.reset();
    }

    @Test
    void cachedSecondCall() throws Exception {
        AiStubs.GROQ.respond(request -> Reply.content(SUMMARY));

        for (int call = 0; call < 2; call++) {
            mockMvc.perform(post("/api/reports/{id}/explain", submission).with(signedIn(owner, Role.USER))
                            .with(xsrf(mockMvc)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.submissionId").value(submission.toString()))
                    .andExpect(jsonPath("$.explanation").value(SUMMARY_TEXT))
                    .andExpect(jsonPath("$.aiWritten").value(true));
        }

        assertThat(explainCalls()).as("the second call reads report.explanation").isEqualTo(1);
        assertThat(storedExplanation()).isEqualTo(SUMMARY_TEXT);
        String user = AiStubs.GROQ.requests().get(0).json().path("messages").get(1).path("content").stringValue();
        assertThat(user).contains("Money request", "Pay ₹5,000 refundable deposit", "Young domain")
                .doesNotContain(LETTER_MARKER);
    }

    @Test
    void aiUnavailable_template() throws Exception {
        AiStubs.GROQ.respond(request -> Reply.status(503, ""));

        String body = mockMvc.perform(post("/api/reports/{id}/explain", submission).with(signedIn(owner, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiWritten").value(false))
                .andReturn().getResponse().getContentAsString();

        String explanation = JsonPath.read(body, "$.explanation");
        assertThat(explanation).startsWith("Strong scam indicators. Do not pay anything.")
                .contains("Money request", "Pressure language", "could not run this time: Young domain");
        assertThat(explanation.toLowerCase()).doesNotContain("verified", "safe", "legit", "guarantee");
        assertThat(storedExplanation()).as("a template is not saved, so the AI is tried again later").isNull();

        // P13 (S3): for 10 minutes a retry gets the same template without asking Groq again (unit-tested expiry).
        int aiRequests = AiStubs.GROQ.requests().size();
        AiStubs.GROQ.respond(request -> Reply.content(SUMMARY));
        mockMvc.perform(post("/api/reports/{id}/explain", submission).with(signedIn(owner, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiWritten").value(false))
                .andExpect(jsonPath("$.explanation").value(explanation));
        assertThat(AiStubs.GROQ.requests()).hasSize(aiRequests);
    }

    @Test
    void forbiddenWordingFromAi_template() throws Exception {
        AiStubs.GROQ.respond(request -> Reply.content("{\"summary\":\"Good news: this offer is safe and verified, so "
                + "you can pay the deposit and join the company without any worry at all.\"}"));

        mockMvc.perform(post("/api/reports/{id}/explain", submission).with(signedIn(owner, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiWritten").value(false));

        assertThat(storedExplanation()).isNull();
    }

    @Test
    void notOwner_404() throws Exception {
        AiStubs.GROQ.respond(request -> Reply.content(SUMMARY));

        String foreign = mockMvc.perform(post("/api/reports/{id}/explain", submission)
                        .with(signedIn(stranger, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String random = mockMvc.perform(post("/api/reports/{id}/explain", UUID.randomUUID())
                        .with(signedIn(stranger, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutRequestFields(foreign)).isEqualTo(withoutRequestFields(random));
        assertThat(explainCalls()).as("no AI call for someone else's report").isZero();
        assertThat(storedExplanation()).isNull();
    }

    @Test
    void tpoOrAdminWhoIsNotOwner_404() throws Exception {
        mockMvc.perform(post("/api/reports/{id}/explain", submission).with(signedIn(stranger, Role.ADMIN))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutXsrf_403() throws Exception {
        mockMvc.perform(post("/api/reports/{id}/explain", submission).with(signedIn(owner, Role.USER)))
                .andExpect(status().isForbidden());

        assertThat(explainCalls()).isZero();
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(post("/api/reports/{id}/explain", submission).with(xsrf(mockMvc)))
                .andExpect(status().isUnauthorized());
    }

    private long explainCalls() {
        return AiStubs.GROQ.requests().stream().filter(request -> request.body().contains("BEGIN REPORT")).count();
    }

    private String storedExplanation() {
        return jdbc.queryForObject("SELECT explanation FROM report WHERE id = ?", String.class, bytes(submission));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static String withoutRequestFields(String problem) {
        return problem.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }

    private UUID user() {
        String key = UUID.randomUUID().toString();
        return provisioning.provision(new OidcIdentity("sub-p7-" + key, "student-p7-" + key + "@example.com", true,
                "Test Student")).userId();
    }

    private UUID upload(UUID user) throws Exception {
        String body = mockMvc.perform(multipart("/api/submissions")
                        .param("text", "Dear Candidate, this fictional letter " + LETTER_MARKER
                                + " asks you to pay a refundable deposit within 24 hours.")
                        .header("Idempotency-Key", "p7-explain-" + UUID.randomUUID())
                        .with(signedIn(user, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }
}
