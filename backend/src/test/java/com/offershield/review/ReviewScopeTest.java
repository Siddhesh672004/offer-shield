package com.offershield.review;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
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
 * Who may open and see review cases (PRD §6.5, §14.3; D-04): the owner, approved now in the college the submission
 * was made in, opens one case per submission; anyone else (another student, a student not approved, approved in
 * another college) gets 404 like a random ID; a TPO sees and acts only on their own college's cases (another college →
 * 404); a wrong role → 403. With file storage off, a PDF link is 404 {@code PDF_NOT_AVAILABLE}. Fake data only.
 */
class ReviewScopeTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    private CaseFixtures fixtures;
    private UUID college;
    private UUID otherCollege;
    private UUID tpo;
    private UUID otherTpo;
    private UUID student;
    private UUID submission;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new CaseFixtures(context, mockMvc);
        college = fixtures.college();
        otherCollege = fixtures.college();
        tpo = fixtures.tpo(college);
        otherTpo = fixtures.tpo(otherCollege);
        student = fixtures.user();
        fixtures.approve(student, college);
        submission = fixtures.analysed(student, college, CaseFixtures.domain(), List.of(CaseFixtures.key()), null);
    }

    @Test
    void own_201_listedForStudentAndTpo() throws Exception {
        String body = ask(student, submission)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").value(submission.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(get("/api/reviews").with(signedIn(student, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(caseId.toString()));
        String queue = mockMvc.perform(get("/api/reviews/queue").with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> mine = JsonPath.read(queue, "$[?(@.id == '" + caseId + "')]");
        assertThat(mine).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("studentEmail", fixtures.email(student)).containsEntry("version", 0)
                    .containsEntry("pdfAvailable", false).containsEntry("status", "OPEN");
            assertThat(JsonPath.parse(item).read("$.evidence.band", String.class)).isEqualTo("HIGH");
            assertThat(JsonPath.parse(item).read("$.evidence.markers", List.class)).isEmpty();
        });
        mockMvc.perform(get("/api/reviews/{id}", caseId).with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(caseId.toString()))
                .andExpect(jsonPath("$.evidence.signals[0].code").value("C1"))
                .andExpect(jsonPath("$.evidence.phoneHmacs").doesNotExist());
    }

    @Test
    void notOwnerOrNotApproved_404() throws Exception {
        UUID classmate = fixtures.user();
        fixtures.approve(classmate, college);
        UUID notMember = fixtures.user();
        UUID notMembersSubmission = fixtures.analysed(notMember, college, null, List.of(CaseFixtures.key()), null);
        UUID elsewhere = fixtures.user();
        fixtures.approve(elsewhere, otherCollege);
        UUID madeInFirstCollege = fixtures.analysed(elsewhere, college, null, List.of(CaseFixtures.key()), null);
        UUID offCampus = fixtures.user();
        fixtures.approve(offCampus, college);
        UUID offCampusSubmission = fixtures.analysed(offCampus, null, null, List.of(CaseFixtures.key()), null);

        String foreign = ask(classmate, submission).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String random = ask(classmate, UUID.randomUUID()).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutRequestFields(foreign)).isEqualTo(withoutRequestFields(random));
        ask(notMember, notMembersSubmission).andExpect(status().isNotFound());
        ask(elsewhere, madeInFirstCollege).andExpect(status().isNotFound());
        ask(offCampus, offCampusSubmission).andExpect(status().isNotFound());
        assertThat(caseRows()).isZero();
    }

    @Test
    void notAnalysedYet_404() throws Exception {
        ask(student, fixtures.submission(student)).andExpect(status().isNotFound());
    }

    @Test
    void reportReadyButSnapshotMissing_owner409ReportNotReady_othersSame404() throws Exception {
        // P13 (silent failure 1): the owner's finished report whose registry copy is not stored yet is "try again in a
        // minute", not a misleading 404; anyone else still gets the same 404 as a random ID (no leak).
        UUID pending = fixtures.analysedWithoutSnapshot(student, college);
        UUID classmate = fixtures.user();
        fixtures.approve(classmate, college);

        ask(student, pending)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPORT_NOT_READY"))
                .andExpect(jsonPath("$.detail").value("This report is still being prepared. Try again in a minute."));
        String foreign = ask(classmate, pending).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String random = ask(classmate, UUID.randomUUID()).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutRequestFields(foreign)).isEqualTo(withoutRequestFields(random));
        assertThat(caseRows()).isZero();
    }

    @Test
    void concurrentAsk_one201one409() throws Exception {
        // P13 (test gap): two clicks at once reach the UNIQUE (submission_id) branch: 201 and 409, never 500.
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Integer> statuses = new java.util.ArrayList<>();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                var csrf = xsrf(mockMvc);
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"submissionId\":\"" + submission + "\"}")
                                    .with(signedIn(student, Role.USER)).with(csrf))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(caseRows()).isEqualTo(1);
    }

    @Test
    void list_excludesOtherStudentsCases() throws Exception {
        UUID classmate = fixtures.user();
        fixtures.approve(classmate, college);
        UUID classmatesSubmission = fixtures.analysed(classmate, college, null, List.of(CaseFixtures.key()), null);
        String classmatesCase = JsonPath.read(ask(classmate, classmatesSubmission).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        UUID mine = openCase();

        String body = mockMvc.perform(get("/api/reviews").with(signedIn(student, Role.USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$[*].id");
        assertThat(ids).contains(mine.toString()).doesNotContain(classmatesCase);
        assertThat(body).doesNotContain(classmatesSubmission.toString());
    }

    @Test
    void duplicate_409() throws Exception {
        ask(student, submission).andExpect(status().isCreated());

        ask(student, submission)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_EXISTS"));
        assertThat(caseRows()).isEqualTo(1);
    }

    @Test
    void otherCollege_404() throws Exception {
        UUID caseId = openCase();

        mockMvc.perform(get("/api/reviews/{id}", caseId).with(signedIn(otherTpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        mockMvc.perform(decision(caseId).with(signedIn(otherTpo, Role.TPO)).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reviews/{id}/pdf-link", caseId).with(signedIn(otherTpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        String queue = mockMvc.perform(get("/api/reviews/queue").with(signedIn(otherTpo, Role.TPO)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(queue).doesNotContain(caseId.toString());
        assertThat(fixtures.auditRowsFor(caseId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM review_case WHERE id = ?", String.class,
                Ids.toBytes(caseId))).isEqualTo("OPEN");
    }

    @Test
    void wrongRole_403() throws Exception {
        UUID caseId = openCase();
        UUID admin = fixtures.admin();

        mockMvc.perform(get("/api/reviews/queue").with(signedIn(student, Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reviews/queue").with(signedIn(admin, Role.ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reviews/{id}", caseId).with(signedIn(student, Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(decision(caseId).with(signedIn(student, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + submission + "\"}")
                        .with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc)))
                .andExpect(status().isForbidden());
    }

    @Test
    void pdfLink_storageOff_404NotAvailable() throws Exception {
        UUID caseId = openCase();
        fixtures.storedPdf(submission, "submissions/" + "a".repeat(64));

        mockMvc.perform(get("/api/reviews/{id}/pdf-link", caseId).with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PDF_NOT_AVAILABLE"))
                // P13 (silent failure 18): the wording covers every reason, including retention.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("deleted after 30 days")));
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(get("/api/reviews/queue")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reviews")).andExpect(status().isUnauthorized());
    }

    private UUID openCase() throws Exception {
        String body = ask(student, submission).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions ask(UUID user, UUID submissionId) throws Exception {
        return mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"submissionId\":\"" + submissionId + "\"}")
                .with(signedIn(user, Role.USER)).with(xsrf(mockMvc)));
    }

    private static MockHttpServletRequestBuilder decision(UUID caseId) {
        return post("/api/reviews/{id}/decision", caseId).header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRMED_SCAM\"}");
    }

    private int caseRows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM review_case WHERE college_id = ?", Integer.class,
                Ids.toBytes(college));
        return count == null ? 0 : count;
    }

    private static String withoutRequestFields(String problemJson) {
        return problemJson.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }
}
