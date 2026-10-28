package com.offershield.submission;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.CollegeRepository;
import com.offershield.membership.MembershipRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/submissions} end to end on MySQL 8.4 and the embedded broker: the 202 contract, S-06 (PRD §14.4:
 * .exe renamed to .pdf → 415, 6 MB → 413, 11 pages → 422), form validation without echoing values, roles and CSRF.
 * Nothing invalid is ever saved.
 */
class SubmissionUploadIT extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private JdbcTemplate jdbc;

    private SubmissionFixtures fixtures;
    private UUID user;

    @BeforeEach
    void setUp() {
        fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        user = fixtures.user();
    }

    @Test
    void validPdf_202_queued_pdfNotStoredWithoutCloudinary() throws Exception {
        String body = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey())
                        .file(SubmissionFixtures.pdf(TestPdfs.onePage()))
                        .param("senderEmail", SubmissionFixtures.SENDER))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.submissionId");

        mockMvc.perform(get("/api/submissions/{id}", id).with(signedIn(user, Role.USER)))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.sourceType").value("PDF"))
                .andExpect(jsonPath("$.pdfStored").value(false));
        assertThat(jdbc.queryForObject("SELECT sender_domain FROM submission WHERE id = ?", String.class,
                SubmissionFixtures.idBytes(UUID.fromString(id)))).isEqualTo("fictional-hiring.example");
    }

    @Test
    void exeRenamedAsPdf_415() throws Exception {
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).file(SubmissionFixtures.pdf(TestPdfs.exeRenamed())))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_FILE_TYPE"));
        assertThat(SubmissionFixtures.count(jdbc, user)).isZero();
    }

    @Test
    void sixMegabytes_413() throws Exception {
        byte[] sixMegabytes = TestPdfs.pdfHeaderOfSize(6 * 1024 * 1024);

        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).file(SubmissionFixtures.pdf(sixMegabytes)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));
        assertThat(SubmissionFixtures.count(jdbc, user)).isZero();
    }

    @Test
    void elevenPages_422() throws Exception {
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).file(SubmissionFixtures.pdf(TestPdfs.withPages(11))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_PAGES"));
        assertThat(SubmissionFixtures.count(jdbc, user)).isZero();
    }

    @Test
    void encryptedPdf_422() throws Exception {
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).file(SubmissionFixtures.pdf(TestPdfs.encrypted())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("PDF_ENCRYPTED"));
    }

    @Test
    void imageOnlyPdf_422_pasteTheTextInstead() throws Exception {
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).file(SubmissionFixtures.pdf(TestPdfs.imageOnly())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("TEXT_TOO_SHORT"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("paste the text")));
    }

    @Test
    void fileAndText_400() throws Exception {
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey())
                        .file(SubmissionFixtures.pdf(TestPdfs.onePage())).param("text", SubmissionFixtures.LETTER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("FILE_OR_TEXT"));
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("FILE_OR_TEXT"));
    }

    @Test
    void pastedTextOver20000Chars_400() throws Exception {
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", "a".repeat(20_001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("text"));
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", "a".repeat(20_000)))
                .andExpect(status().isAccepted());
    }

    @Test
    void invalidContacts_400_valuesNeverEchoed() throws Exception {
        String body = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey())
                        .param("text", SubmissionFixtures.LETTER)
                        .param("senderEmail", "ZEBRA-not-an-email")
                        .param("recruiterPhone", "ZEBRA-call-me")
                        .param("upiId", "ZEBRA no upi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<java.util.List<String>>read(body, "$.violations[*].field"))
                .containsExactlyInAnyOrder("recruiterPhone", "senderEmail", "upiId");
        assertThat(body).doesNotContain("ZEBRA");
        assertThat(SubmissionFixtures.count(jdbc, user)).isZero();
    }

    @Test
    void blankOptionalFields_ignored() throws Exception {
        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey())
                        .param("text", SubmissionFixtures.LETTER)
                        .param("senderEmail", "")
                        .param("recruiterPhone", "  ")
                        .param("upiId", ""))
                .andExpect(status().isAccepted());
    }

    @Test
    void tpoAndAdmin_403() throws Exception {
        for (Role role : new Role[] {Role.TPO, Role.ADMIN}) {
            mockMvc.perform(multipart("/api/submissions").param("text", SubmissionFixtures.LETTER)
                            .header("Idempotency-Key", SubmissionFixtures.newKey())
                            .with(signedIn(fixtures.userWithRole(users, role), role)).with(xsrf(mockMvc)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }
    }

    @Test
    void withoutCsrfToken_403() throws Exception {
        mockMvc.perform(multipart("/api/submissions").param("text", SubmissionFixtures.LETTER)
                        .header("Idempotency-Key", SubmissionFixtures.newKey())
                        .with(signedIn(user, Role.USER)))
                .andExpect(status().isForbidden());
        assertThat(SubmissionFixtures.count(jdbc, user)).isZero();
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(multipart("/api/submissions").param("text", SubmissionFixtures.LETTER)
                        .header("Idempotency-Key", SubmissionFixtures.newKey()).with(xsrf(mockMvc)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jsonBody_415() throws Exception {
        mockMvc.perform(post("/api/submissions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}")
                        .header("Idempotency-Key", SubmissionFixtures.newKey())
                        .with(signedIn(user, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().exists("X-Request-Id"));
    }
}
