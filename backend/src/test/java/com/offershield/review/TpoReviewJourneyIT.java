package com.offershield.review;

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
 * Journey J5 and drill F-06 (05 §3, §5; PRD §4 F5, §6.5, §13.8; D-04, D-33.7) through the real pipeline, with real
 * SMTP: an approved student's letter → "Ask my TPO" → the TPO's queue shows the evidence → the PDF link is "not
 * available" for pasted text → CONFIRMED_SCAM → a second TPO of the college, still on version 0, gets 409 and the case
 * keeps the first decision → the student is emailed once → another student's copy is "Confirmed scam". Fictional
 * letter and fake numbers.
 */
class TpoReviewJourneyIT extends MailIntegrationTest {

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
    void j5_askTpo_confirmedScam_secondTab409_studentEmailed() throws Exception {
        UUID college = fixtures.college();
        UUID tpo = fixtures.tpo(college);
        UUID secondTpo = fixtures.tpo(college);
        UUID student = fixtures.user();
        fixtures.approve(student, college);
        String phone = Journeys.phone();
        String letter = letter(phone);

        UUID submission = journeys.analyse(student, letter, null);
        mockMvc.perform(get("/api/submissions/{id}", submission).with(signedIn(student, Role.USER)))
                .andExpect(jsonPath("$.onCampus").value(true));
        String created = mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + submission + "\"}")
                        .with(signedIn(student, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(JsonPath.read(created, "$.id"));

        // The TPO's queue: the case with its evidence (D-04), and no PDF for pasted text.
        String queue = mockMvc.perform(get("/api/reviews/queue").with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> item = JsonPath.read(queue, "$[?(@.id == '" + caseId + "')]");
        assertThat(item).singleElement().satisfies(found -> {
            assertThat(found).containsEntry("studentEmail", fixtures.email(student)).containsEntry("version", 0);
            assertThat(JsonPath.parse(found).read("$.evidence.band", String.class)).isEqualTo("HIGH");
            List<String> codes = JsonPath.parse(found).read("$.evidence.signals[*].code");
            assertThat(codes).contains("C1", "C8");
        });
        // Only the signals' short, masked evidence: never the whole letter or the phone number.
        assertThat(queue).doesNotContain("Do not call the office").doesNotContain(phone.substring(4));
        mockMvc.perform(get("/api/reviews/{id}/pdf-link", caseId).with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PDF_NOT_AVAILABLE"));

        // Tab 1 decides; tab 2 (another TPO, still on version 0) gets 409 and the case keeps the first decision.
        mockMvc.perform(post("/api/reviews/{id}/decision", caseId).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED_SCAM\",\"note\":\"No such company is registered.\"}")
                        .with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED_SCAM"));
        mockMvc.perform(post("/api/reviews/{id}/decision", caseId).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRMED_GENUINE\"}")
                        .with(signedIn(secondTpo, Role.TPO)).with(xsrf(mockMvc)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
        mockMvc.perform(get("/api/reviews/{id}", caseId).with(signedIn(secondTpo, Role.TPO)))
                .andExpect(jsonPath("$.status").value("CONFIRMED_SCAM"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(fixtures.auditRows(tpo, "REVIEW_CONFIRMED_SCAM", caseId)).isEqualTo(1);
        assertThat(fixtures.auditRowsFor(caseId)).isEqualTo(1);

        awaitEmailsSent();
        MimeMessage[] emails = GREEN_MAIL.getReceivedMessages();
        assertThat(emails).singleElement().satisfies(email -> {
            assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(student));
            assertThat(email.getContent().toString()).contains("marked it as a scam")
                    .contains("Note from your TPO: No such company is registered.");
            assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
        });
        mockMvc.perform(get("/api/reviews").with(signedIn(student, Role.USER)))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED_SCAM"));

        // The TPO's confirmation protects everyone: another student's copy is "Confirmed scam".
        UUID other = fixtures.user();
        String otherReport = journeys.report(other, journeys.analyse(other, letter, null));
        assertThat((Boolean) JsonPath.read(otherReport, "$.registry.confirmedScam")).isTrue();
        assertThat((String) JsonPath.read(otherReport, "$.band")).isEqualTo("HIGH");
    }

    /** A fictional registration-fee scam with WhatsApp-only contact, over 50 words, whose phone number varies. */
    private static String letter(String phone) {
        return "Hello Candidate,\n\nCongratulations, your profile was shortlisted by Orchid Lane Recruiters (a "
                + "fictional agency) for a work from home Data Entry Executive role at a monthly stipend of Rs. 28,000. "
                + "There is no interview. To issue your appointment letter, pay the one-time Rs. 2,500 registration "
                + "charge today and message the receipt on WhatsApp only to " + phone + ". Do not call the office, as "
                + "all onboarding happens over chat, and the offer lapses at midnight for candidates who delay.\n\n"
                + "Hiring Team\nOrchid Lane Recruiters\n";
    }
}
