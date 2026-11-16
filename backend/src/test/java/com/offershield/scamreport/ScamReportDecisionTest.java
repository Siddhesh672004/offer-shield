package com.offershield.scamreport;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.Role;
import com.offershield.common.CopyRules;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.IndicatorView;
import com.offershield.support.CaseFixtures;
import com.offershield.support.MailIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.offershield.scamreport.ScamReportAccessTest.report;
import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin's moderation (PRD §6.4 steps 5–7, §13.8; D-04, D-22, D-33.4, D-33.7, R-E) with real SMTP: the queue shows
 * the snapshot evidence, marker reporter counts and the version, never the letter text or keys; confirm upgrades the
 * markers to CONFIRMED; reject withdraws the reporter (a marker nobody else reported becomes REJECTED); a stale or
 * missing If-Match is refused; only the admin may act; each decision writes an audit row and emails the reporter.
 */
class ScamReportDecisionTest extends MailIntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private IndicatorService indicators;

    private CaseFixtures fixtures;
    private UUID admin;
    private UUID reporter;
    private UUID submission;
    private String domain;
    private String phoneKey;
    private UUID reportId;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new CaseFixtures(context, mockMvc);
        admin = fixtures.admin();
        reporter = fixtures.user();
        domain = CaseFixtures.domain();
        phoneKey = CaseFixtures.key();
        submission = fixtures.analysed(reporter, null, domain, List.of(phoneKey), CaseFixtures.simhash());
        String body = mockMvc.perform(report(submission, 5000).with(signedIn(reporter, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        reportId = UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @Test
    void queue_showsEvidenceAndVersion_noLetterOrKeys() throws Exception {
        String body = mockMvc.perform(get("/api/admin/scam-reports").param("status", "OPEN")
                        .with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> mine = JsonPath.read(body, "$[?(@.id == '" + reportId + "')]");
        assertThat(mine).hasSize(1);
        String item = JsonPath.parse(mine.get(0)).jsonString();
        assertThat((String) JsonPath.read(item, "$.status")).isEqualTo("OPEN");
        assertThat((String) JsonPath.read(item, "$.submissionId")).isEqualTo(submission.toString());
        assertThat((String) JsonPath.read(item, "$.description")).contains("refundable kit deposit");
        assertThat((Integer) JsonPath.read(item, "$.amountAsked")).isEqualTo(5000);
        assertThat((Integer) JsonPath.read(item, "$.version")).isZero();
        assertThat((Boolean) JsonPath.read(item, "$.pdfAvailable")).isFalse();
        assertThat((String) JsonPath.read(item, "$.evidence.band")).isEqualTo("HIGH");
        assertThat((String) JsonPath.read(item, "$.evidence.bandMessage"))
                .isEqualTo("Strong scam indicators. Do not pay anything.");
        assertThat((String) JsonPath.read(item, "$.evidence.senderDomain")).isEqualTo(domain);
        assertThat((String) JsonPath.read(item, "$.evidence.signals[0].title")).isEqualTo("Money request");
        assertThat((String) JsonPath.read(item, "$.evidence.signals[0].evidence"))
                .isEqualTo("pay Rs. 5,000 refundable deposit");
        List<String> types = JsonPath.read(item, "$.evidence.markers[*].type");
        assertThat(types).containsExactly("DOMAIN", "PHONE", "SIMHASH");
        List<Integer> counts = JsonPath.read(item, "$.evidence.markers[*].reporterCount");
        assertThat(counts).containsOnly(1);
        assertThat(item).doesNotContain(phoneKey).doesNotContain("phoneHmacs").doesNotContain("simhash\"")
                .doesNotContain(reporter.toString()).doesNotContain(fixtures.email(reporter));
    }

    @Test
    void confirm_upgrades() throws Exception {
        decide("\"0\"", "CONFIRM")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(reportId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.evidence.markers[*].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("CONFIRMED"))));

        assertThat(indicators.markersOf(submission)).hasSize(3)
                .allSatisfy(marker -> assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED));
        MimeMessage email = singleEmail();
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(reporter));
        assertThat(email.getSubject()).isEqualTo("OfferShield: update on your scam report");
        assertThat(email.getContent().toString()).contains("reviewed your scam report and confirmed it")
                .doesNotContain("refundable kit deposit");
        assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
    }

    @Test
    void reject_cleanup() throws Exception {
        // Another person reported the same phone number: their report keeps that marker UNVERIFIED (D-33.4).
        UUID other = fixtures.user();
        UUID otherSubmission = fixtures.analysed(other, null, null, List.of(phoneKey), null);
        mockMvc.perform(report(otherSubmission, null).with(signedIn(other, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated());

        decide("0", "REJECT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        List<IndicatorView> markers = indicators.markersOf(submission);
        assertThat(markers).filteredOn(marker -> marker.type() == IndicatorType.PHONE).singleElement()
                .satisfies(marker -> {
                    assertThat(marker.status()).isEqualTo(IndicatorStatus.UNVERIFIED);
                    assertThat(marker.reporterCount()).isEqualTo(1);
                });
        assertThat(markers).filteredOn(marker -> marker.type() != IndicatorType.PHONE).hasSize(2)
                .allSatisfy(marker -> {
                    assertThat(marker.status()).isEqualTo(IndicatorStatus.REJECTED);
                    assertThat(marker.reporterCount()).isZero();
                });
        MimeMessage email = singleEmail();
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(reporter));
        assertThat(email.getContent().toString()).contains("could not confirm it");
        assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
    }

    @Test
    void decision_markersRecordedCounted() throws Exception {
        // P13 (silent failure 7): CONFIRM says how many markers it confirmed; REJECT records none.
        decide("0", "CONFIRM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markersRecorded").value(3));

        UUID other = fixtures.user();
        UUID otherSubmission = fixtures.analysed(other, null, null, List.of(CaseFixtures.key()), null);
        UUID otherReport = UUID.fromString(JsonPath.read(mockMvc.perform(report(otherSubmission, null)
                        .with(signedIn(other, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
        mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", otherReport).header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}")
                        .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markersRecorded").value(0));
    }

    @Test
    void confirm_noMarkers_zeroAndEmailMakesNoFlaggingClaim() throws Exception {
        UUID other = fixtures.user();
        UUID bare = fixtures.analysed(other, null, "gmail.com", List.of(), null);
        UUID bareReport = UUID.fromString(JsonPath.read(mockMvc.perform(report(bare, null)
                        .with(signedIn(other, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.markersRecorded").value(0))
                .andReturn().getResponse().getContentAsString(), "$.id"));

        mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", bareReport).header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")
                        .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.markersRecorded").value(0));

        MimeMessage email = singleEmail();
        assertThat(email.getContent().toString()).contains("confirmed it")
                .contains("No scam markers could be recorded for this letter")
                .doesNotContain("now flagged");
        assertThat(CopyRules.violates(email.getSubject() + " " + email.getContent())).isFalse();
    }

    @Test
    void evidenceMarkers_phoneShowsSourceAndMaskedHint() throws Exception {
        // P13 (N2): the admin sees where a phone marker came from and a masked hint, never the key.
        UUID other = fixtures.user();
        String key = CaseFixtures.key();
        UUID hinted = fixtures.submission(other);
        fixtures.snapshotWithHint(hinted, other, key, "TYPED", "••••9012");
        mockMvc.perform(report(hinted, null).with(signedIn(other, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/admin/scam-reports").param("status", "OPEN")
                        .with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> item = JsonPath.read(body, "$[?(@.submissionId == '" + hinted + "')]");
        String json = JsonPath.parse(item.get(0)).jsonString();
        assertThat((String) JsonPath.read(json, "$.evidence.markers[0].type")).isEqualTo("PHONE");
        assertThat((String) JsonPath.read(json, "$.evidence.markers[0].source")).isEqualTo("TYPED");
        assertThat((String) JsonPath.read(json, "$.evidence.markers[0].hint")).isEqualTo("••••9012");
        assertThat(json).doesNotContain(key);
    }

    @Test
    void concurrentConfirmAndReject_one200one409() throws Exception {
        // P13 (test gap): the report row is locked for the decision, so one admin action wins and the other gets 409.
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String decision : List.of("CONFIRM", "REJECT")) {
                var csrf = xsrf(mockMvc);
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", reportId)
                                    .header("If-Match", "0").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"decision\":\"" + decision + "\"}")
                                    .with(signedIn(admin, Role.ADMIN)).with(csrf))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(fixtures.auditRowsFor(reportId)).isEqualTo(1);
    }

    @Test
    void stale_409() throws Exception {
        decide("\"5\"", "CONFIRM")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        assertThat(indicators.markersOf(submission))
                .allSatisfy(marker -> assertThat(marker.status()).isEqualTo(IndicatorStatus.UNVERIFIED));
        assertThat(fixtures.auditRowsFor(reportId)).isZero();
        awaitEmailsSent();
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }

    @Test
    void alreadyDecided_409() throws Exception {
        decide("0", "CONFIRM").andExpect(status().isOk());

        decide("1", "REJECT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_DECIDED"));
        assertThat(fixtures.auditRowsFor(reportId)).isEqualTo(1);
    }

    @Test
    void missingIfMatch_428_unknown_404() throws Exception {
        mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", reportId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")
                        .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", UUID.randomUUID())
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")
                        .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void nonAdmin_403() throws Exception {
        UUID tpo = fixtures.tpo(fixtures.college());

        mockMvc.perform(get("/api/admin/scam-reports").with(signedIn(reporter, Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/scam-reports").with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isForbidden());
        for (var principal : List.of(signedIn(reporter, Role.USER), signedIn(tpo, Role.TPO))) {
            mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", reportId).header("If-Match", "0")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}")
                            .with(principal).with(xsrf(mockMvc)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }
        assertThat(indicators.markersOf(submission))
                .allSatisfy(marker -> assertThat(marker.status()).isEqualTo(IndicatorStatus.UNVERIFIED));
    }

    @Test
    void audited() throws Exception {
        decide("0", "CONFIRM").andExpect(status().isOk());
        assertThat(fixtures.auditRows(admin, "SCAM_REPORT_CONFIRMED", reportId)).isEqualTo(1);

        UUID other = fixtures.user();
        UUID otherSubmission = fixtures.analysed(other, null, null, List.of(CaseFixtures.key()), null);
        String body = mockMvc.perform(report(otherSubmission, null).with(signedIn(other, Role.USER))
                        .with(xsrf(mockMvc)))
                .andReturn().getResponse().getContentAsString();
        UUID otherReport = UUID.fromString(JsonPath.read(body, "$.id"));
        mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", otherReport).header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}")
                        .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isOk());
        assertThat(fixtures.auditRows(admin, "SCAM_REPORT_REJECTED", otherReport)).isEqualTo(1);
    }

    private ResultActions decide(String ifMatch, String decision) throws Exception {
        return mockMvc.perform(post("/api/admin/scam-reports/{id}/decision", reportId).header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"" + decision + "\"}")
                .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)));
    }

    private MimeMessage singleEmail() throws Exception {
        awaitEmailsSent();
        assertThat(GREEN_MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }
}
