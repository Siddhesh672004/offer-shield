package com.offershield.review;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.Role;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-05, M-07, PRD §9.1, §14.3 (S-03, PDF-link part): a signed, private, 5-minute download link to the original PDF is
 * given only to the TPO of the review case's college and to the admin for a reported submission. Another college's
 * TPO and a random ID get the same 404; a student (wrong role) gets 403; a letter without a stored PDF is 404
 * {@code PDF_NOT_AVAILABLE}; the link is never logged. File storage is configured here with fake credentials: signing
 * happens locally in the Cloudinary SDK, so nothing reaches Cloudinary.
 */
@TestPropertySource(properties = "offershield.cloudinary.url="
        + "cloudinary://123456789012345:fake-secret-for-pdf-link-tests@demo-cloud")
class PdfLinkAccessTest extends IntegrationTest {

    private static final String PUBLIC_ID = "submissions/" + "b".repeat(64);
    private static final Pattern EXPIRES_AT = Pattern.compile("expires_at=(\\d+)");

    @Autowired
    private ApplicationContext context;

    private CaseFixtures fixtures;
    private UUID tpo;
    private UUID otherTpo;
    private UUID student;
    private UUID admin;
    private UUID caseId;
    private UUID scamReportId;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new CaseFixtures(context, mockMvc);
        UUID college = fixtures.college();
        tpo = fixtures.tpo(college);
        otherTpo = fixtures.tpo(fixtures.college());
        admin = fixtures.admin();
        student = fixtures.user();
        fixtures.approve(student, college);
        UUID submission = fixtures.analysed(student, college, CaseFixtures.domain(), List.of(CaseFixtures.key()),
                null);
        fixtures.storedPdf(submission, PUBLIC_ID);
        caseId = id(mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + submission + "\"}")
                        .with(signedIn(student, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        scamReportId = id(mockMvc.perform(post("/api/scam-reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + submission + "\",\"description\":\"Asked for a deposit.\"}")
                        .with(signedIn(student, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void tpoOwnCollege_ok() throws Exception {
        long before = Instant.now().getEpochSecond();
        String body = mockMvc.perform(get("/api/reviews/{id}/pdf-link", caseId).with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFiveMinuteSignedLink(body, before);
        mockMvc.perform(get("/api/reviews/{id}", caseId).with(signedIn(tpo, Role.TPO)))
                .andExpect(jsonPath("$.pdfAvailable").value(true));
    }

    @Test
    void tpoOtherCollege_404() throws Exception {
        String foreign = mockMvc.perform(get("/api/reviews/{id}/pdf-link", caseId).with(signedIn(otherTpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String random = mockMvc.perform(get("/api/reviews/{id}/pdf-link", UUID.randomUUID())
                        .with(signedIn(otherTpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutRequestFields(foreign)).isEqualTo(withoutRequestFields(random));
        assertThat(foreign).doesNotContain("cloudinary");
    }

    /** CLAUDE.md invariant 3: a wrong role is 403 before any lookup (04 named this case "student_404"). */
    @Test
    void student_403() throws Exception {
        mockMvc.perform(get("/api/reviews/{id}/pdf-link", caseId).with(signedIn(student, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.url").doesNotExist());
        mockMvc.perform(get("/api/admin/scam-reports/{id}/pdf-link", scamReportId).with(signedIn(student, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.url").doesNotExist());
        mockMvc.perform(get("/api/admin/scam-reports/{id}/pdf-link", scamReportId).with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_ok() throws Exception {
        long before = Instant.now().getEpochSecond();
        String body = mockMvc.perform(get("/api/admin/scam-reports/{id}/pdf-link", scamReportId)
                        .with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFiveMinuteSignedLink(body, before);
        mockMvc.perform(get("/api/admin/scam-reports/{id}/pdf-link", UUID.randomUUID())
                        .with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void noStoredPdf_404NotAvailable() throws Exception {
        UUID college = fixtures.college();
        UUID otherStudent = fixtures.user();
        fixtures.approve(otherStudent, college);
        UUID pasted = fixtures.analysed(otherStudent, college, null, List.of(CaseFixtures.key()), null);
        UUID pastedCase = id(mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + pasted + "\"}")
                        .with(signedIn(otherStudent, Role.USER)).with(xsrf(mockMvc)))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/reviews/{id}/pdf-link", pastedCase).with(signedIn(fixtures.tpo(college), Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PDF_NOT_AVAILABLE"));
    }

    @Test
    void link_neverLogged() throws Exception {
        String url;
        try (LogCapture logs = LogCapture.root()) {
            url = JsonPath.read(mockMvc.perform(get("/api/reviews/{id}/pdf-link", caseId)
                            .with(signedIn(tpo, Role.TPO)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(), "$.url");
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .isNotEmpty()
                    .noneSatisfy(line -> assertThat(line).containsAnyOf("api.cloudinary.com", "signature=",
                            "fake-secret-for-pdf-link-tests", PUBLIC_ID));
        }
        assertThat(url).contains("signature=");
    }

    private static void assertFiveMinuteSignedLink(String body, long before) {
        String url = JsonPath.read(body, "$.url");
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
        URI uri = URI.create(url);
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("api.cloudinary.com");
        assertThat(uri.getPath()).isEqualTo("/v1_1/demo-cloud/image/download");
        assertThat(url).contains("type=authenticated").contains("format=pdf").contains("signature=")
                .doesNotContain("fake-secret-for-pdf-link-tests");
        Matcher matcher = EXPIRES_AT.matcher(url);
        assertThat(matcher.find()).isTrue();
        assertThat(Long.parseLong(matcher.group(1))).isEqualTo(expiresAt.getEpochSecond())
                .isBetween(before + 299, Instant.now().getEpochSecond() + 300);
    }

    private static UUID id(String json) {
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private static String withoutRequestFields(String problemJson) {
        return problemJson.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }
}
