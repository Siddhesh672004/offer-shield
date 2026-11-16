package com.offershield.scamreport;

import com.offershield.auth.Role;
import com.offershield.common.Ids;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRD §14.3 / §6.4 step 2 (S-03, scam-report part): {@code POST /api/scam-reports} accepts only the signed-in user's
 * own analysed submission; another user's submission and a random ID get the same 404, and nothing is stored. Wrong
 * role → 403, no XSRF → 403, signed out → 401. The description is stored encrypted. Fake data only.
 */
class ScamReportAccessTest extends IntegrationTest {

    private static final String DESCRIPTION = "They asked me to pay a refundable kit deposit before joining.";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    private CaseFixtures fixtures;
    private UUID owner;
    private UUID stranger;
    private UUID submission;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new CaseFixtures(context, mockMvc);
        owner = fixtures.user();
        stranger = fixtures.user();
        submission = fixtures.analysed(owner, null, CaseFixtures.domain(), List.of(CaseFixtures.key()),
                CaseFixtures.simhash());
    }

    @Test
    void own_201() throws Exception {
        mockMvc.perform(report(submission, 5000).with(signedIn(owner, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.submissionId").value(submission.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.amountAsked").value(5000))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.decidedAt").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist());

        mockMvc.perform(get("/api/scam-reports").with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].submissionId").value(submission.toString()));
        mockMvc.perform(get("/api/scam-reports").with(signedIn(stranger, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void otherUsersSubmission_404() throws Exception {
        mockMvc.perform(report(submission, null).with(signedIn(stranger, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        assertThat(reportRows(submission)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM indicator_reporter WHERE submission_id = ?",
                Integer.class, Ids.toBytes(submission))).isZero();
    }

    @Test
    void foreignAndRandomId_sameBody() throws Exception {
        String foreign = mockMvc.perform(report(submission, null).with(signedIn(stranger, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String random = mockMvc.perform(report(UUID.randomUUID(), null).with(signedIn(stranger, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutRequestFields(foreign)).isEqualTo(withoutRequestFields(random));
        assertThat(foreign).doesNotContain(owner.toString());
    }

    @Test
    void notAnalysedYet_404() throws Exception {
        UUID queued = fixtures.submission(owner);

        mockMvc.perform(report(queued, null).with(signedIn(owner, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound());
        assertThat(reportRows(queued)).isZero();
    }

    @Test
    void wrongRole_403() throws Exception {
        UUID tpo = fixtures.tpo(fixtures.college());
        UUID admin = fixtures.admin();

        mockMvc.perform(report(submission, null).with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mockMvc.perform(report(submission, null).with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isForbidden());
        assertThat(reportRows(submission)).isZero();
    }

    @Test
    void withoutXsrf_403() throws Exception {
        mockMvc.perform(report(submission, null).with(signedIn(owner, Role.USER)))
                .andExpect(status().isForbidden());
        assertThat(reportRows(submission)).isZero();
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(report(submission, null).with(xsrf(mockMvc))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/scam-reports")).andExpect(status().isUnauthorized());
    }

    @Test
    void description_storedEncrypted() throws Exception {
        mockMvc.perform(report(submission, null).with(signedIn(owner, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated());

        byte[] stored = jdbc.queryForObject("SELECT description_cipher FROM scam_report WHERE submission_id = ?",
                byte[].class, Ids.toBytes(submission));
        assertThat(stored).isNotNull().hasSizeGreaterThan(12 + 16);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("refundable");
    }

    private int reportRows(UUID submissionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scam_report WHERE submission_id = ?",
                Integer.class, Ids.toBytes(submissionId));
        return count == null ? 0 : count;
    }

    static MockHttpServletRequestBuilder report(UUID submissionId, Integer amountAsked) {
        String amount = amountAsked == null ? "" : ",\"amountAsked\":" + amountAsked;
        return post("/api/scam-reports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"submissionId\":\"" + submissionId + "\",\"description\":\"" + DESCRIPTION + "\""
                        + amount + "}");
    }

    private static String withoutRequestFields(String problemJson) {
        return problemJson.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }
}
