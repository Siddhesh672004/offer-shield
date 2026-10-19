package com.offershield.membership;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.CollegeRepository;
import com.offershield.support.MailIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/memberships/pending and POST /api/memberships/{id}/decision (P3-02, D-22, R-D, R-E), with real SMTP. */
class MembershipDecisionTest extends MailIntegrationTest {

    private static final String REASON = "Roll number not on the placement list";

    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private JdbcTemplate jdbc;

    private MembershipFixtures fixtures;
    private UUID college;
    private UUID tpo;
    private UUID student;

    @BeforeEach
    void setUp() {
        fixtures = new MembershipFixtures(provisioning, users, colleges);
        college = fixtures.college("Decision");
        tpo = fixtures.tpo(college);
        student = fixtures.student();
    }

    @Test
    void approve_ok_emailSent_audited() throws Exception {
        UUID id = join(student, college);

        decide(id, "\"0\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.collegeId").value(college.toString()))
                .andExpect(jsonPath("$.collegeName").value(fixtures.collegeName(college)))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reason").value(nullValue()))
                .andExpect(jsonPath("$.version").value(1));

        Membership saved = memberships.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.APPROVED);
        assertThat(saved.getDecidedBy()).isEqualTo(tpo);
        assertThat(saved.getDecidedAt()).isNotNull();
        assertThat(auditRows(tpo, "MEMBERSHIP_APPROVED", id)).isEqualTo(1);

        MimeMessage email = singleEmail();
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(student));
        assertThat(email.getFrom()).extracting(Object::toString).containsExactly(SMTP_USER);
        assertThat(email.getContent().toString())
                .contains("Your request to join " + fixtures.collegeName(college) + " was approved");
        assertNoForbiddenWords(email);
    }

    @Test
    void reject_withReason_emailedAndAudited() throws Exception {
        UUID id = join(student, college);

        decide(id, "0", "{\"decision\":\"REJECT\",\"reason\":\"  " + REASON + "  \"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value(REASON));

        assertThat(auditRows(tpo, "MEMBERSHIP_REJECTED", id)).isEqualTo(1);
        MimeMessage email = singleEmail();
        assertThat(email.getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(student));
        assertThat(email.getContent().toString())
                .contains("Your request to join " + fixtures.collegeName(college) + " was not approved. Reason: " + REASON);
        assertNoForbiddenWords(email);
    }

    @Test
    void staleVersion_409() throws Exception {
        UUID id = join(student, college);

        decide(id, "\"7\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        assertUnchangedPending(id);
    }

    @Test
    void otherCollege_404() throws Exception {
        UUID otherCollege = fixtures.college("Other");
        UUID otherTpo = fixtures.tpo(otherCollege);
        UUID id = join(student, college);

        mockMvc.perform(decision(id, "0", "{\"decision\":\"APPROVE\"}").with(signedIn(otherTpo, Role.TPO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        assertUnchangedPending(id);
        assertThat(auditRows(otherTpo, "MEMBERSHIP_APPROVED", id)).isZero();
    }

    @Test
    void unknownMembership_404() throws Exception {
        decide(UUID.randomUUID(), "0", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void rejectWithoutReason_400() throws Exception {
        UUID id = join(student, college);

        decide(id, "0", "{\"decision\":\"REJECT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("REASON_REQUIRED"));
        decide(id, "0", "{\"decision\":\"REJECT\",\"reason\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REASON_REQUIRED"));

        assertUnchangedPending(id);
    }

    @Test
    void reasonTooLong_400() throws Exception {
        UUID id = join(student, college);

        decide(id, "0", "{\"decision\":\"REJECT\",\"reason\":\"" + "r".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());

        assertUnchangedPending(id);
    }

    @Test
    void unknownDecision_400() throws Exception {
        UUID id = join(student, college);

        decide(id, "0", "{\"decision\":\"MAYBE\"}").andExpect(status().isBadRequest());
        decide(id, "0", "{}").andExpect(status().isBadRequest());

        assertUnchangedPending(id);
    }

    @Test
    void rejectedCanReapply() throws Exception {
        UUID id = join(student, college);
        decide(id, "0", "{\"decision\":\"REJECT\",\"reason\":\"" + REASON + "\"}").andExpect(status().isOk());
        UUID nextCollege = fixtures.college("Next");
        UUID nextTpo = fixtures.tpo(nextCollege);

        mockMvc.perform(post("/api/memberships").with(signedIn(student, Role.USER)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(joinBody(nextCollege, "IT-201", "Information Technology")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.collegeId").value(nextCollege.toString()))
                .andExpect(jsonPath("$.rollNo").value("IT-201"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reason").value(nullValue()))
                .andExpect(jsonPath("$.version").value(2));

        Membership saved = memberships.findById(id).orElseThrow();
        assertThat(saved.getDecidedBy()).isNull();
        assertThat(saved.getDecidedAt()).isNull();
        mockMvc.perform(get("/api/memberships/pending").with(signedIn(nextTpo, Role.TPO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].version").value(2));
        mockMvc.perform(get("/api/memberships/pending").with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void missingIfMatch_428() throws Exception {
        UUID id = join(student, college);

        mockMvc.perform(post("/api/memberships/{id}/decision", id).with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("PRECONDITION_REQUIRED"));

        assertUnchangedPending(id);
    }

    @Test
    void malformedIfMatch_400() throws Exception {
        UUID id = join(student, college);

        decide(id, "W/\"0\"", "{\"decision\":\"APPROVE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_IF_MATCH"));

        assertUnchangedPending(id);
    }

    @Test
    void alreadyDecided_409() throws Exception {
        UUID id = join(student, college);
        decide(id, "0", "{\"decision\":\"APPROVE\"}").andExpect(status().isOk());

        decide(id, "1", "{\"decision\":\"REJECT\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        assertThat(memberships.findById(id).orElseThrow().getStatus()).isEqualTo(MembershipStatus.APPROVED);
        assertThat(auditRows(tpo, "MEMBERSHIP_REJECTED", id)).isZero();
    }

    @Test
    void nonTpo_403() throws Exception {
        UUID id = join(student, college);

        mockMvc.perform(decision(id, "0", "{\"decision\":\"APPROVE\"}").with(signedIn(student, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mockMvc.perform(get("/api/memberships/pending").with(signedIn(student, Role.USER)))
                .andExpect(status().isForbidden());

        assertUnchangedPending(id);
    }

    @Test
    void sessionTpoWithoutCollegeInDb_403() throws Exception {
        UUID id = join(student, college);
        UUID demoted = fixtures.student();

        mockMvc.perform(decision(id, "0", "{\"decision\":\"APPROVE\"}").with(signedIn(demoted, Role.TPO)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/memberships/pending").with(signedIn(demoted, Role.TPO)))
                .andExpect(status().isForbidden());

        assertUnchangedPending(id);
    }

    @Test
    void pending_ownCollegeOnly_oldestFirst() throws Exception {
        UUID second = fixtures.student();
        UUID first = join(student, college);
        UUID next = join(second, college);
        join(fixtures.student(), fixtures.college("Elsewhere"));

        mockMvc.perform(get("/api/memberships/pending").with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.toString()))
                .andExpect(jsonPath("$[0].studentName").value(fixtures.name(student)))
                .andExpect(jsonPath("$[0].studentEmail").value(fixtures.email(student)))
                .andExpect(jsonPath("$[0].rollNo").value("RN-1"))
                .andExpect(jsonPath("$[0].department").value("Computer Engineering"))
                .andExpect(jsonPath("$[0].requestedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].version").value(0))
                .andExpect(jsonPath("$[1].id").value(next.toString()));
    }

    private UUID join(UUID who, UUID where) throws Exception {
        String body = mockMvc.perform(post("/api/memberships").with(signedIn(who, Role.USER)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(joinBody(where, "RN-1", "Computer Engineering")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions decide(UUID id, String ifMatch, String body) throws Exception {
        return mockMvc.perform(decision(id, ifMatch, body).with(signedIn(tpo, Role.TPO)));
    }

    private MockHttpServletRequestBuilder decision(UUID id, String ifMatch, String body) throws Exception {
        return post("/api/memberships/{id}/decision", id).with(xsrf(mockMvc)).header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String joinBody(UUID college, String rollNo, String department) {
        return "{\"collegeId\":\"%s\",\"rollNo\":\"%s\",\"department\":\"%s\"}".formatted(college, rollNo, department);
    }

    private Integer auditRows(UUID actor, String action, UUID target) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id = UUID_TO_BIN(?) AND action = ? "
                + "AND target_id = UUID_TO_BIN(?)", Integer.class, actor.toString(), action, target.toString());
    }

    private void assertUnchangedPending(UUID id) {
        Membership saved = memberships.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(saved.getVersion()).isZero();
        assertThat(GREEN_MAIL.waitForIncomingEmail(300, 1)).as("no email for a refused decision").isFalse();
    }

    private static MimeMessage singleEmail() {
        assertThat(GREEN_MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    private static void assertNoForbiddenWords(MimeMessage email) throws Exception {
        String text = (email.getSubject() + " " + email.getContent()).toLowerCase();
        assertThat(text).doesNotContain("verified", "safe", "legit", "guaranteed");
    }
}
