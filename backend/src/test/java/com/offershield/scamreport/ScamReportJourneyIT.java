package com.offershield.scamreport;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.Role;
import com.offershield.common.CopyRules;
import com.offershield.support.CaseFixtures;
import com.offershield.support.Journeys;
import com.offershield.support.MailIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Journeys J3 and J4 (05 §5; PRD §6.4, §7.3; D-13, D-33.7) through the real pipeline on the embedded broker, with real
 * SMTP. J3: user A's deposit scam is High → A reports it → user B's copy shows "Reported as a scam by 1 user (not
 * verified)", never as confirmed. J4: the admin's queue shows A's report with evidence → the PDF link is "not
 * available" for pasted text → Confirm → A is emailed → user C's copy is "Confirmed scam" (C10 override) → Revert →
 * user D's copy is no longer confirmed (A's report still counts as unverified). Fictional letter and fake numbers.
 */
class ScamReportJourneyIT extends MailIntegrationTest {

    @Autowired
    private ApplicationContext context;

    private Journeys journeys;
    private CaseFixtures fixtures;

    @BeforeEach
    void setUp() {
        journeys = new Journeys(context, mockMvc);
        fixtures = new CaseFixtures(context, mockMvc);
        journeys.startConsumers();
    }

    @AfterEach
    void tearDown() {
        journeys.stopConsumers();
    }

    @Test
    void j3_reportedByOneNotVerified_j4_confirmEmailRevert() throws Exception {
        String domain = Journeys.healthyDomain("talentdesk-careers");
        String letter = letter(Journeys.phone());
        String sender = "onboarding@" + domain;

        // J3: A uploads the deposit scam and reports it.
        UUID userA = fixtures.user();
        UUID submissionA = journeys.analyse(userA, letter, sender);
        String reportA = journeys.report(userA, submissionA);
        assertThat((String) JsonPath.read(reportA, "$.band")).isEqualTo("HIGH");
        String created = mockMvc.perform(post("/api/scam-reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + submissionA + "\",\"description\":\"They asked for a "
                                + "Rs. 3,000 kit deposit before joining and stopped answering.\",\"amountAsked\":3000}")
                        .with(signedIn(userA, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID reportId = UUID.fromString(JsonPath.read(created, "$.id"));

        // B uploads the same letter: an unverified warning, never shown as confirmed.
        UUID userB = fixtures.user();
        String reportB = journeys.report(userB, journeys.analyse(userB, letter, sender));
        assertThat((Integer) JsonPath.read(reportB, "$.registry.reportedBy")).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(reportB, "$.registry.confirmedScam")).isFalse();
        Map<String, Object> c12 = Journeys.signal(reportB, "C12");
        assertThat(c12).containsEntry("outcome", "TRIGGERED").containsEntry("sourceKind", 5)
                .containsEntry("hardOverride", false);
        assertThat((String) c12.get("explanation")).startsWith("Reported as a scam by 1 user (not verified).");
        assertThat(Journeys.signal(reportB, "C10")).containsEntry("outcome", "PASSED");
        mockMvc.perform(get("/api/registry/domains/{domain}", domain).with(signedIn(userB, Role.USER)))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.message").value("Reported as a scam by 1 user (not verified)"));

        // J4: the admin's unverified queue shows A's report with its evidence.
        UUID admin = fixtures.admin();
        String queue = mockMvc.perform(get("/api/admin/scam-reports").with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> item = JsonPath.read(queue, "$[?(@.id == '" + reportId + "')]");
        assertThat(item).singleElement().satisfies(found -> {
            assertThat(found).containsEntry("status", "OPEN").containsEntry("version", 0)
                    .containsEntry("amountAsked", 3000);
            List<String> types = JsonPath.parse(found).read("$.evidence.markers[*].type");
            assertThat(types).contains("DOMAIN", "PHONE", "SIMHASH");
        });
        mockMvc.perform(get("/api/admin/scam-reports/{id}/pdf-link", reportId).with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PDF_NOT_AVAILABLE"));

        // Confirm → the reporter is emailed.
        String decided = mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", reportId)
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM\"}")
                        .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();
        awaitEmailsSent();
        MimeMessage[] emails = GREEN_MAIL.getReceivedMessages();
        assertThat(emails).singleElement().satisfies(email -> {
            assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(userA));
            assertThat(email.getContent().toString()).contains("confirmed it");
            assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
        });

        // C's identical letter: "Confirmed scam", High by the C10 override.
        UUID userC = fixtures.user();
        String reportC = journeys.report(userC, journeys.analyse(userC, letter, sender));
        assertThat((Boolean) JsonPath.read(reportC, "$.registry.confirmedScam")).isTrue();
        assertThat((String) JsonPath.read(reportC, "$.band")).isEqualTo("HIGH");
        assertThat(Journeys.signal(reportC, "C10")).containsEntry("outcome", "TRIGGERED")
                .containsEntry("hardOverride", true).containsEntry("sourceKind", 4);

        // Revert every confirmed marker (D-13) → D's identical letter is no longer confirmed.
        List<Map<String, Object>> markers = JsonPath.read(decided, "$.evidence.markers");
        assertThat(markers).isNotEmpty().allSatisfy(marker -> assertThat(marker).containsEntry("status", "CONFIRMED"));
        for (Map<String, Object> marker : markers) {
            mockMvc.perform(post("/api/admin/indicators/{id}/revert", marker.get("id"))
                            .header("If-Match", "\"" + marker.get("version") + "\"")
                            .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNVERIFIED"));
        }
        UUID userD = fixtures.user();
        String reportD = journeys.report(userD, journeys.analyse(userD, letter, sender));
        assertThat((Boolean) JsonPath.read(reportD, "$.registry.confirmedScam")).isFalse();
        assertThat(Journeys.signal(reportD, "C10")).containsEntry("outcome", "PASSED");
        assertThat(Journeys.signal(reportD, "C11")).containsEntry("outcome", "PASSED");
        assertThat((Integer) JsonPath.read(reportD, "$.registry.reportedBy")).isEqualTo(1);
        assertThat(fixtures.auditRows(admin, "SCAM_REPORT_CONFIRMED", reportId)).isEqualTo(1);
        for (Map<String, Object> marker : markers) {
            assertThat(fixtures.auditRows(admin, "INDICATOR_REVERTED", UUID.fromString((String) marker.get("id"))))
                    .isEqualTo(1);
        }
    }

    /** A fictional deposit scam, well over 50 words (so it has a wording fingerprint), whose phone number varies. */
    private static String letter(String phone) {
        return "Dear Applicant,\n\nWe are glad to inform you that you cleared the online aptitude round for the post "
                + "of Junior Process Associate with Brightwave Talent Desk (a fictional firm). To lock your seat in the "
                + "induction batch, pay the refundable kit and laptop deposit of Rs. 3,000 within six hours and share "
                + "the payment screenshot with our induction coordinator on " + phone + ". Candidates who miss the "
                + "window lose the seat to the waiting list, and the joining kit is couriered only after the deposit "
                + "is verified by accounts.\n\nInduction Cell\nBrightwave Talent Desk\n";
    }
}
