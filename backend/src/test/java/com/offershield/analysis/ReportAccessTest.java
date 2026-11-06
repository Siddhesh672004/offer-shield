package com.offershield.analysis;

import com.jayway.jsonpath.JsonPath;
import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.checks.CheckResult;
import com.offershield.analysis.scoring.Band;
import com.offershield.analysis.scoring.Score;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.common.SenderSource;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRD §14.3 / §11 / D-04 (S-03, report part): {@code GET /api/reports/{submissionId}} answers only the owner; another
 * user's report and a random ID get the same 404. Also pins the report JSON the frontend (P12) reads.
 */
class ReportAccessTest extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private ReportService reports;
    @Autowired
    private ApplicationContext context;

    private UUID owner;
    private UUID stranger;
    private UUID submission;

    @BeforeEach
    void setUp() throws Exception {
        owner = user();
        stranger = user();
        submission = upload(owner);
        reports.save(UUID.randomUUID(), new NewReport(submission, owner, null, "young-firm.com",
                new Score(30, Band.HIGH, true), true, List.of(
                CheckResult.override(CheckCode.C1, "The letter asks you to pay money.", "Pay ₹5,000 refundable deposit", 1),
                CheckResult.unavailable(CheckCode.C5, "The registry gave no date.", 2),
                CheckResult.triggered(CheckCode.C9, 10, "Pressure language.", "“within 24 hours”", 1),
                CheckResult.triggered(CheckCode.C2, 20, "Free email sender.", "Sender address ends in @gmail.com", 1))));
    }

    @Test
    void own_200() throws Exception {
        mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(submission.toString()))
                .andExpect(jsonPath("$.score").value(30))
                .andExpect(jsonPath("$.band").value("HIGH"))
                .andExpect(jsonPath("$.bandMessage").value("Strong scam indicators. Do not pay anything."))
                .andExpect(jsonPath("$.hardOverride").value(true))
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.senderDomain").value("young-firm.com"))
                .andExpect(jsonPath("$.unavailableChecks", hasSize(1)))
                .andExpect(jsonPath("$.unavailableChecks[0]").value("C5"))
                // P9: the registry summary (no C10–C12 signal in this report), never the HMAC keys or SimHash.
                .andExpect(jsonPath("$.registry.confirmedScam").value(false))
                .andExpect(jsonPath("$.registry.wordingWarning").value(false))
                .andExpect(jsonPath("$.registry.reportedBy").value(0))
                .andExpect(jsonPath("$.phoneHmacs").doesNotExist())
                .andExpect(jsonPath("$.simhash").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.signals", hasSize(4)))
                // in check order
                .andExpect(jsonPath("$.signals[0].code").value("C1"))
                .andExpect(jsonPath("$.signals[0].title").value("Money request"))
                .andExpect(jsonPath("$.signals[0].outcome").value("TRIGGERED"))
                .andExpect(jsonPath("$.signals[0].points").value(0))
                .andExpect(jsonPath("$.signals[0].hardOverride").value(true))
                .andExpect(jsonPath("$.signals[0].explanation").value("The letter asks you to pay money."))
                .andExpect(jsonPath("$.signals[0].evidence").value("Pay ₹5,000 refundable deposit"))
                .andExpect(jsonPath("$.signals[0].sourceKind").value(1))
                .andExpect(jsonPath("$.signals[0].aiExtracted").value(false))
                .andExpect(jsonPath("$.signals[1].code").value("C2"))
                .andExpect(jsonPath("$.signals[2].code").value("C5"))
                .andExpect(jsonPath("$.signals[2].outcome").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.signals[2].evidence").doesNotExist())
                .andExpect(jsonPath("$.signals[3].code").value("C9"));
    }

    @Test
    void otherUsersReport_404() throws Exception {
        mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(stranger, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void foreignAndRandomId_sameBody() throws Exception {
        String foreign = mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(stranger, Role.USER)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String random = mockMvc.perform(get("/api/reports/{id}", UUID.randomUUID()).with(signedIn(stranger, Role.USER)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutRequestFields(foreign)).isEqualTo(withoutRequestFields(random));
        assertThat(foreign).doesNotContain(owner.toString()).doesNotContain("deposit");
    }

    @Test
    void notAnalysedYet_404() throws Exception {
        UUID queued = upload(owner);

        mockMvc.perform(get("/api/reports/{id}", queued).with(signedIn(owner, Role.USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(get("/api/reports/{id}", submission)).andExpect(status().isUnauthorized());
    }

    @Test
    void tpoOrAdminNotOwner_404() throws Exception {
        // D-04: TPOs and the admin see evidence only through review cases and scam reports, never the owner's report.
        CaseFixtures fixtures = new CaseFixtures(context, mockMvc);
        UUID tpo = fixtures.tpo(fixtures.college());
        UUID admin = fixtures.admin();

        mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void senderSourceAndHints_storedForTheEventShownAsSourceOnly() throws Exception {
        // P13 (S2, N2): the report JSON says where the sender address came from; the masked hints go only to the
        // registry (analysis.completed), never into the student's report JSON.
        UUID letterSourced = upload(owner);
        String key = "c".repeat(64);
        LetterMarkers markers = new LetterMarkers("young-firm.com", List.of(key), List.of(), null, java.util.Set.of(),
                true, List.of(new AnalysisCompletedEvent.MarkerHint("PHONE", key, "TYPED", "••••1234")));
        reports.save(UUID.randomUUID(), new NewReport(letterSourced, owner, null, "young-firm.com",
                new Score(0, Band.LOW, false), false, List.of(CheckResult.passed(CheckCode.C1, "No money request.", 1)),
                false, AiDetails.NONE, markers, 0, SenderSource.LETTER, 2));

        String body = mockMvc.perform(get("/api/reports/{id}", letterSourced).with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderSource").value("LETTER"))
                .andExpect(jsonPath("$.markerHints").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(key).doesNotContain("1234");
        mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(owner, Role.USER)))
                .andExpect(jsonPath("$.senderSource").value(org.hamcrest.Matchers.nullValue()));

        AnalysisCompletedEvent.Payload payload = reports.findCompletion(letterSourced).orElseThrow().payload();
        assertThat(payload.senderSource()).isEqualTo("LETTER");
        assertThat(payload.letterEmailDomains()).isEqualTo(2);
        assertThat(payload.markerHints()).containsExactly(
                new AnalysisCompletedEvent.MarkerHint("PHONE", key, "TYPED", "••••1234"));
    }

    @Test
    void duplicateSave_ignored() {
        boolean again = reports.save(UUID.randomUUID(), new NewReport(submission, owner, null, null,
                new Score(0, Band.LOW, false), false, List.of(CheckResult.passed(CheckCode.C1, "No money request.", 1))));

        assertThat(again).isFalse();
        assertThat(reports.findCompletion(submission)).hasValueSatisfying(completion ->
                assertThat(completion.payload().band()).isEqualTo("HIGH"));
    }

    private UUID user() {
        String key = UUID.randomUUID().toString();
        return provisioning.provision(new OidcIdentity("sub-p6-" + key, "student-p6-" + key + "@example.com", true,
                "Test Student")).userId();
    }

    private UUID upload(UUID user) throws Exception {
        String body = mockMvc.perform(multipart("/api/submissions")
                        .param("text", "Dear Candidate, this fictional letter asks you to pay a refundable deposit.")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(signedIn(user, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }

    private static String withoutRequestFields(String problemJson) {
        return problemJson.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }
}
