package com.offershield.analysis;

import com.jayway.jsonpath.JsonPath;
import com.offershield.analysis.checks.Letters;
import com.offershield.auth.AppUser;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.support.IntegrationTest;
import com.offershield.support.LookupStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Journey J6 (05 §5) and milestone M5 without a Google sign-in (pending secrets): the admin adds a known company and its
 * official domain through the API, then a student's look-alike letter goes through the real pipeline (upload →
 * Kafka → analysis → report) and is flagged by C3 and C4; the same letter from the official domain is not. The PRD's
 * done-when ({@code inf0sys-careers.com} is flagged) runs end to end too. Companies exist only for the test and are
 * deleted through the API afterwards. Fictional letter prose only (05 §4).
 */
class KnownCompanyLookalikeIT extends IntegrationTest {

    private static final Instant OLD = Instant.now().minus(Duration.ofDays(4000));

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private final List<UUID> created = new ArrayList<>();
    private UUID adminId;
    private UUID student;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString();
        adminId = provisioning.provision(new OidcIdentity("sub-j6-admin-" + key, "admin-j6-" + key + "@example.com",
                true, "Test Admin")).userId();
        AppUser admin = users.findById(adminId).orElseThrow();
        admin.changeRole(Role.ADMIN);
        users.saveAndFlush(admin);
        student = provisioning.provision(new OidcIdentity("sub-j6-student-" + key, "student-j6-" + key + "@example.com",
                true, "Test Student")).userId();
        registry.getListenerContainer(SubmissionCreatedListener.ID).start();
        registry.getListenerContainer(com.offershield.submission.SubmissionStatusListener.ID).start();
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.getListenerContainer(SubmissionCreatedListener.ID).stop();
        registry.getListenerContainer(com.offershield.submission.SubmissionStatusListener.ID).stop();
        for (UUID id : created) {
            mockMvc.perform(delete("/api/known-companies/{id}", id).with(signedIn(adminId, Role.ADMIN))
                    .with(xsrf(mockMvc))).andExpect(status().isNoContent());
        }
    }

    @Test
    void j6_adminAddsCompany_lookalikeLetterFlaggedC3C4_officialSenderNot() throws Exception {
        healthyDomain("n0rthwind-analytics.com");
        healthyDomain("northwind-analytics.com");
        addCompany("Northwind Analytics", "northwind-analytics.com");
        String letter = Letters.fixture(Letters.GENUINE); // "Northwind Analytics Private Limited (a fictional company)"

        String lookalike = analyse(letter, "hr@n0rthwind-analytics.com");
        Map<String, Object> c3 = signal(lookalike, "C3");
        Map<String, Object> c4 = signal(lookalike, "C4");
        assertThat(c3).containsEntry("outcome", "TRIGGERED").containsEntry("points", 20).containsEntry("sourceKind", 3)
                .containsEntry("aiExtracted", false).containsEntry("title", "Domain doesn't match the company");
        assertThat((String) c3.get("evidence")).contains("Northwind Analytics", "n0rthwind-analytics.com",
                "northwind-analytics.com");
        assertThat(c4).containsEntry("outcome", "TRIGGERED").containsEntry("points", 30).containsEntry("sourceKind", 3)
                .containsEntry("title", "Lookalike domain");
        assertThat((Integer) JsonPath.read(lookalike, "$.score")).isGreaterThanOrEqualTo(50);
        assertThat((String) JsonPath.read(lookalike, "$.band")).isEqualTo("HIGH");
        assertThat((Boolean) JsonPath.read(lookalike, "$.hardOverride")).isFalse();

        String official = analyse(letter, "talent@northwind-analytics.com");
        assertThat(signal(official, "C3")).containsEntry("outcome", "PASSED").containsEntry("points", 0);
        assertThat(signal(official, "C4")).containsEntry("outcome", "PASSED").containsEntry("points", 0);
        assertThat((String) JsonPath.read(official, "$.band")).isEqualTo("LOW");
    }

    @Test
    void m5_inf0sysCareersSender_flaggedC4() throws Exception {
        healthyDomain("inf0sys-careers.com");
        addCompany("Infosys", "infosys.com"); // PRD F8's own example, as a list entry and domain strings only

        String report = analyse("Dear Candidate,\n\nWe are pleased to offer you the role of Trainee Analyst. Your "
                + "joining date and documents will follow by email.\n\nRegards,\nHR Team\n", "hr@inf0sys-careers.com");

        Map<String, Object> c4 = signal(report, "C4");
        assertThat(c4).containsEntry("outcome", "TRIGGERED").containsEntry("points", 30);
        assertThat((String) c4.get("evidence")).contains("inf0sys-careers.com", "Infosys", "infosys.com");
        assertThat(signal(report, "C3")).as("the letter names no known company")
                .containsEntry("outcome", "NOT_APPLICABLE");
        assertThat((String) JsonPath.read(report, "$.band")).isEqualTo("MEDIUM");
    }

    private void addCompany(String name, String domain) throws Exception {
        String body = mockMvc.perform(post("/api/known-companies").with(signedIn(adminId, Role.ADMIN)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\", \"domains\": [\"" + domain + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        created.add(UUID.fromString(JsonPath.read(body, "$.id")));
    }

    /** An old domain with MX and DMARC, so C5/C6 add nothing and the score shows C3/C4 alone. */
    private static void healthyDomain(String domain) {
        LookupStubs.RDAP.registered(domain, OLD);
        LookupStubs.DNS.mx(domain, "mx." + domain).txt("_dmarc." + domain, "v=DMARC1; p=reject");
    }

    private String analyse(String text, String senderEmail) throws Exception {
        String body = mockMvc.perform(multipart("/api/submissions").param("text", text).param("senderEmail", senderEmail)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(signedIn(student, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.submissionId"));
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String submission = mockMvc.perform(get("/api/submissions/{id}", id).with(signedIn(student, Role.USER)))
                    .andReturn().getResponse().getContentAsString();
            return !"QUEUED".equals(JsonPath.read(submission, "$.status"));
        });
        return mockMvc.perform(get("/api/reports/{id}", id).with(signedIn(student, Role.USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Object> signal(String report, String code) {
        List<Map<String, Object>> signals = JsonPath.read(report, "$.signals[?(@.code == '" + code + "')]");
        assertThat(signals).as(code).hasSize(1);
        return signals.get(0);
    }
}
