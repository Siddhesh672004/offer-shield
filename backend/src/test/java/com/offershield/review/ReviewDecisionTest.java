package com.offershield.review;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.Role;
import com.offershield.common.CopyRules;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.support.CaseFixtures;
import com.offershield.support.LogCapture;
import com.offershield.support.MailIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The TPO's decision (PRD §4 F5, §6.5, §13.8; D-08, D-22, D-33.7, R-E) with real SMTP: CONFIRMED_SCAM confirms the
 * letter's markers straight away, except a shared (free-mail) domain, which never becomes a marker; CONFIRMED_GENUINE
 * and INCONCLUSIVE leave the registry alone; a stale or missing If-Match is refused; every decision writes an audit
 * row and emails the student, with the TPO's note. Fake data only.
 */
class ReviewDecisionTest extends MailIntegrationTest {

    private static final String NOTE = "The company has no office at the address in the letter.";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private IndicatorService indicators;
    @Autowired
    private JdbcTemplate jdbc;

    private CaseFixtures fixtures;
    private UUID college;
    private UUID tpo;
    private UUID student;

    @BeforeEach
    void setUp() {
        fixtures = new CaseFixtures(context, mockMvc);
        college = fixtures.college();
        tpo = fixtures.tpo(college);
        student = fixtures.user();
        fixtures.approve(student, college);
    }

    @Test
    void confirmedScam_upgrades() throws Exception {
        UUID submission = fixtures.analysed(student, college, CaseFixtures.domain(), List.of(CaseFixtures.key()),
                CaseFixtures.simhash());
        UUID caseId = openCase(submission);

        decide(caseId, "\"0\"", "CONFIRMED_SCAM", NOTE)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("CONFIRMED_SCAM"))
                .andExpect(jsonPath("$.note").value(NOTE))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.evidence.markers.length()").value(3))
                // P13 (silent failure 7): how many scam markers the decision confirmed.
                .andExpect(jsonPath("$.markersRecorded").value(3));

        assertThat(indicators.markersOf(submission)).hasSize(3).allSatisfy(marker -> {
            assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED);
            assertThat(marker.confirmedAt()).isNotNull();
        });
        MimeMessage email = singleEmail();
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(student));
        assertThat(email.getSubject()).isEqualTo("OfferShield: your TPO reviewed your offer letter");
        assertThat(email.getContent().toString()).contains("marked it as a scam")
                .contains("Note from your TPO: " + NOTE)
                .contains("now flagged as \"Confirmed scam\"");
        assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
    }

    @Test
    void confirmedScam_noMarkers_zeroAndEmailMakesNoFlaggingClaim() throws Exception {
        // P13 (silent failure 7): a letter with nothing the registry can keep (a shared domain, no number, too short)
        // is confirmed with 0 markers; the response and the email say so instead of claiming letters are now flagged.
        UUID submission = fixtures.analysed(student, college, "gmail.com", List.of(), null);
        UUID caseId = openCase(submission);

        try (LogCapture logs = LogCapture.of(ReviewService.class)) {
            decide(caseId, "0", "CONFIRMED_SCAM", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED_SCAM"))
                    .andExpect(jsonPath("$.markersRecorded").value(0));
            assertThat(logs.events()).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("no scam markers recorded"));
        }

        MimeMessage email = singleEmail();
        assertThat(email.getContent().toString()).contains("marked it as a scam")
                .contains("No scam markers could be recorded for this letter")
                .doesNotContain("now flagged");
        assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
    }

    @Test
    void sharedDomainExcluded() throws Exception {
        String phone = CaseFixtures.key();
        UUID submission = fixtures.analysed(student, college, "gmail.com", List.of(phone), null);
        UUID caseId = openCase(submission);

        decide(caseId, "0", "CONFIRMED_SCAM", null).andExpect(status().isOk());

        assertThat(indicators.markersOf(submission)).singleElement().satisfies(marker -> {
            assertThat(marker.type()).isEqualTo(IndicatorType.PHONE);
            assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM indicator WHERE type = 'DOMAIN' AND match_key = "
                + "'gmail.com'", Integer.class)).isZero();
    }

    @Test
    void genuineAndInconclusive_noMarkers_emailed() throws Exception {
        UUID genuine = fixtures.analysed(student, college, CaseFixtures.domain(), List.of(CaseFixtures.key()), null);
        decide(openCase(genuine), "0", "CONFIRMED_GENUINE", "  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED_GENUINE"))
                .andExpect(jsonPath("$.markersRecorded").value(0))
                .andExpect(jsonPath("$.note").doesNotExist());
        MimeMessage email = singleEmail();
        assertThat(email.getContent().toString()).contains("marked it as genuine").doesNotContain("Note from");
        assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
        assertThat(indicators.markersOf(genuine)).isEmpty();

        GREEN_MAIL.purgeEmailFromAllMailboxes();
        UUID unsure = fixtures.analysed(student, college, CaseFixtures.domain(), List.of(CaseFixtures.key()), null);
        decide(openCase(unsure), "0", "INCONCLUSIVE", NOTE).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INCONCLUSIVE"));
        email = singleEmail();
        assertThat(email.getContent().toString()).contains("could not reach a conclusion");
        assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
        assertThat(indicators.markersOf(unsure)).isEmpty();
    }

    @Test
    void audited() throws Exception {
        UUID caseId = openCase(fixtures.analysed(student, college, null, List.of(CaseFixtures.key()), null));

        decide(caseId, "0", "CONFIRMED_SCAM", null).andExpect(status().isOk());

        assertThat(fixtures.auditRows(tpo, "REVIEW_CONFIRMED_SCAM", caseId)).isEqualTo(1);
        assertThat(fixtures.auditRowsFor(caseId)).isEqualTo(1);
    }

    @Test
    void stale_409_alreadyDecided_409_missingIfMatch_428() throws Exception {
        UUID submission = fixtures.analysed(student, college, null, List.of(CaseFixtures.key()), null);
        UUID caseId = openCase(submission);

        decide(caseId, "\"3\"", "CONFIRMED_SCAM", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
        assertThat(indicators.markersOf(submission)).isEmpty();
        assertThat(fixtures.auditRowsFor(caseId)).isZero();

        mockMvc.perform(post("/api/reviews/{id}/decision", caseId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"INCONCLUSIVE\"}")
                        .with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc)))
                .andExpect(status().isPreconditionRequired());

        decide(caseId, "0", "INCONCLUSIVE", null).andExpect(status().isOk());
        decide(caseId, "1", "CONFIRMED_SCAM", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_DECIDED"));
        assertThat(indicators.markersOf(submission)).isEmpty();
    }

    @Test
    void invalidDecision_400() throws Exception {
        UUID caseId = openCase(fixtures.analysed(student, college, null, List.of(CaseFixtures.key()), null));

        for (String body : List.of("{\"decision\":\"OPEN\"}", "{}",
                "{\"decision\":\"INCONCLUSIVE\",\"note\":\"" + "x".repeat(1001) + "\"}")) {
            mockMvc.perform(post("/api/reviews/{id}/decision", caseId).header("If-Match", "0")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc)))
                    .andExpect(status().isBadRequest());
        }
    }

    private UUID openCase(UUID submission) throws Exception {
        String body = mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + submission + "\"}")
                        .with(signedIn(student, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions decide(UUID caseId, String ifMatch, String decision, String note) throws Exception {
        String noteJson = note == null ? "" : ",\"note\":\"" + note + "\"";
        return mockMvc.perform(post("/api/reviews/{id}/decision", caseId).header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\"" + noteJson + "}")
                .with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc)));
    }

    private MimeMessage singleEmail() throws Exception {
        awaitEmailsSent();
        assertThat(GREEN_MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }
}
