package com.offershield.scamreport;

import com.offershield.auth.Role;
import com.offershield.common.Ids;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.IndicatorView;
import com.offershield.support.CaseFixtures;
import com.offershield.support.LogCapture;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRD §6.4 steps 2–3, D-12, D-33.5 against MySQL: one report per submission (409), at most 5 per user in 24 hours
 * (the 6th → 429 {@code SCAM_REPORT_LIMIT} with Retry-After, counted in the database; older reports stop counting),
 * markers become UNVERIFIED with the reporter added once, and a CONFIRMED marker stays CONFIRMED. Fake data only.
 */
class ScamReportServiceTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private IndicatorService indicators;
    @Autowired
    private JdbcTemplate jdbc;

    private CaseFixtures fixtures;
    private UUID reporter;

    @BeforeEach
    void setUp() {
        fixtures = new CaseFixtures(context, mockMvc);
        reporter = fixtures.user();
    }

    @Test
    void duplicate_409() throws Exception {
        UUID submission = fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null);
        mockMvc.perform(report(submission, null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated());

        mockMvc.perform(report(submission, null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCAM_REPORT_EXISTS"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scam_report WHERE submission_id = ?", Integer.class,
                Ids.toBytes(submission))).isEqualTo(1);
    }

    @Test
    void sixthInDay_429() throws Exception {
        List<UUID> submissions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            submissions.add(fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null));
        }
        // Past the upload limit (5 per 10 minutes, D-12) so a sixth letter can be submitted.
        jdbc.update("UPDATE submission SET created_at = created_at - INTERVAL 11 MINUTE WHERE owner_user_id = ?",
                Ids.toBytes(reporter));
        submissions.add(fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(report(submissions.get(i), null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                    .andExpect(status().isCreated());
        }

        String retryAfter = mockMvc.perform(report(submissions.get(5), null).with(signedIn(reporter, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("SCAM_REPORT_LIMIT"))
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 86_400L);
        assertThat(reportCount()).isEqualTo(5);

        // A report older than 24 hours no longer counts.
        jdbc.update("UPDATE scam_report SET created_at = created_at - INTERVAL 25 HOUR WHERE submission_id = ?",
                Ids.toBytes(submissions.get(0)));
        mockMvc.perform(report(submissions.get(5), null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated());
        assertThat(reportCount()).isEqualTo(6);
    }

    @Test
    void markers_unverified_reporterAddedOnce() throws Exception {
        String domain = CaseFixtures.domain();
        UUID submission = fixtures.analysed(reporter, null, domain, List.of(CaseFixtures.key()),
                CaseFixtures.simhash());

        mockMvc.perform(report(submission, 2500).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated());

        List<IndicatorView> markers = indicators.markersOf(submission);
        assertThat(markers).extracting(IndicatorView::type)
                .containsExactly(IndicatorType.DOMAIN, IndicatorType.PHONE, IndicatorType.SIMHASH);
        assertThat(markers).allSatisfy(marker -> {
            assertThat(marker.status()).isEqualTo(IndicatorStatus.UNVERIFIED);
            assertThat(marker.reporterCount()).isEqualTo(1);
        });
    }

    @Test
    void confirmedStaysConfirmed() throws Exception {
        UUID admin = fixtures.admin();
        UUID submission = fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null);
        indicators.confirm(submission, admin);

        mockMvc.perform(report(submission, null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated());

        assertThat(indicators.markersOf(submission)).singleElement().satisfies(marker -> {
            assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED);
            assertThat(marker.reporterCount()).isEqualTo(1);
        });
    }

    @Test
    void markersRecorded_countedInResponse() throws Exception {
        // P13 (silent failure 7): the response says how many scam markers the report added.
        UUID submission = fixtures.analysed(reporter, null, CaseFixtures.domain(), List.of(CaseFixtures.key()),
                CaseFixtures.simhash());

        mockMvc.perform(report(submission, null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.markersRecorded").value(3));
    }

    @Test
    void nothingToRecord_zeroMarkersLoggedNotAnError() throws Exception {
        UUID submission = fixtures.analysed(reporter, null, "gmail.com", List.of(), null);

        try (LogCapture logs = LogCapture.of(ScamReportService.class)) {
            mockMvc.perform(report(submission, null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.markersRecorded").value(0));
            assertThat(logs.events()).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("no scam markers recorded"));
        }
    }

    @Test
    void reportReadyButSnapshotMissing_owner409ReportNotReady_othersSame404() throws Exception {
        // P13 (silent failure 1): "try again in a minute" for the owner; the same 404 as a random ID for anyone else.
        UUID pending = fixtures.analysedWithoutSnapshot(reporter, null);
        UUID stranger = fixtures.user();

        mockMvc.perform(report(pending, null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPORT_NOT_READY"))
                .andExpect(jsonPath("$.detail").value("This report is still being prepared. Try again in a minute."));
        String foreign = mockMvc.perform(report(pending, null).with(signedIn(stranger, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String random = mockMvc.perform(report(UUID.randomUUID(), null).with(signedIn(stranger, Role.USER))
                        .with(xsrf(mockMvc)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreign.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", ""))
                .isEqualTo(random.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", ""));
        assertThat(reportCount()).isZero();
    }

    @Test
    void concurrentDoubleSubmit_one201one409() throws Exception {
        // P13 (test gap): two clicks at once reach the UNIQUE (submission, reporter) branch: 201 and 409, never 500.
        UUID submission = fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                var csrf = xsrf(mockMvc);
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(report(submission, null).with(signedIn(reporter, Role.USER)).with(csrf))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(reportCount()).isEqualTo(1);
        assertThat(indicators.markersOf(submission)).singleElement()
                .satisfies(marker -> assertThat(marker.reporterCount()).isEqualTo(1));
    }

    @Test
    void parallelReportsAtTheDailyLimit_onlyOneAccepted() throws Exception {
        // P13 (S3): the daily count and the insert are atomic per user, so reports sent at once cannot pass 5 a day.
        List<UUID> submissions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            submissions.add(fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null));
        }
        // Past the upload limit (5 per 10 minutes, D-12) so more letters can be submitted.
        jdbc.update("UPDATE submission SET created_at = created_at - INTERVAL 11 MINUTE WHERE owner_user_id = ?",
                Ids.toBytes(reporter));
        for (int i = 0; i < 3; i++) {
            submissions.add(fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null));
        }
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(report(submissions.get(i), null).with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                    .andExpect(status().isCreated());
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 4; i < 7; i++) {
                UUID submission = submissions.get(i);
                var csrf = xsrf(mockMvc);
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(report(submission, null).with(signedIn(reporter, Role.USER)).with(csrf))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 429, 429);
        assertThat(reportCount()).isEqualTo(5);
    }

    @Test
    void invalidBody_400() throws Exception {
        UUID submission = fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null);

        for (String body : List.of(
                "{\"submissionId\":\"" + submission + "\",\"description\":\"   \"}",
                "{\"submissionId\":\"" + submission + "\",\"description\":\"" + "x".repeat(2001) + "\"}",
                "{\"submissionId\":\"" + submission + "\",\"description\":\"Asked for a deposit\",\"amountAsked\":-1}",
                "{\"description\":\"Asked for a deposit\"}")) {
            mockMvc.perform(post("/api/scam-reports").contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(signedIn(reporter, Role.USER)).with(xsrf(mockMvc)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.violations[*].field", hasItem(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is("description"), org.hamcrest.Matchers.is("amountAsked"),
                            org.hamcrest.Matchers.is("submissionId")))));
        }
        assertThat(reportCount()).isZero();
    }

    private int reportCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scam_report WHERE reporter_user_id = ?",
                Integer.class, Ids.toBytes(reporter));
        return count == null ? 0 : count;
    }
}
