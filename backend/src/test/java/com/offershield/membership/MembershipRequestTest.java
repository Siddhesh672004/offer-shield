package com.offershield.membership;

import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.CollegeRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/memberships and GET /api/memberships/me (P3-02, R-C, D-33.3). */
class MembershipRequestTest extends IntegrationTest {

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

    @BeforeEach
    void setUp() {
        fixtures = new MembershipFixtures(provisioning, users, colleges);
    }

    @Test
    void create_pending_201() throws Exception {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Create");

        join(student, college, " CS-101 ", " Computer Engineering ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.collegeId").value(college.toString()))
                .andExpect(jsonPath("$.collegeName").value(fixtures.collegeName(college)))
                .andExpect(jsonPath("$.rollNo").value("CS-101"))
                .andExpect(jsonPath("$.department").value("Computer Engineering"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reason").value(nullValue()))
                .andExpect(jsonPath("$.version").value(0));

        Membership saved = memberships.findByUserId(student).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(saved.getCollegeId()).isEqualTo(college);
    }

    @Test
    void existingPending_409() throws Exception {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Pending");
        join(student, college, "CS-102", "Civil").andExpect(status().isCreated());

        join(student, fixtures.college("Second"), "CS-102", "Civil")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_EXISTS"));

        assertThat(memberships.findByUserId(student).orElseThrow().getCollegeId()).isEqualTo(college);
    }

    @Test
    void existingApproved_409() throws Exception {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Approved");
        Membership membership = memberships.saveAndFlush(Membership.request(student, college, "CS-103", "Civil"));
        membership.approve(fixtures.tpo(college), Instant.now());
        memberships.saveAndFlush(membership);

        join(student, college, "CS-103", "Civil")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MEMBERSHIP_EXISTS"));

        assertThat(memberships.findByUserId(student).orElseThrow().getStatus()).isEqualTo(MembershipStatus.APPROVED);
    }

    @Test
    void unverifiedEmail_403() throws Exception {
        UUID student = fixtures.student(false);

        join(student, fixtures.college("Unverified"), "CS-104", "Civil")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("EMAIL_NOT_VERIFIED"));

        assertThat(memberships.findByUserId(student)).isEmpty();
    }

    @Test
    void unknownCollege_404() throws Exception {
        UUID student = fixtures.student();

        join(student, UUID.randomUUID(), "CS-105", "Civil")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        assertThat(memberships.findByUserId(student)).isEmpty();
    }

    @Test
    void inactiveCollege_404() throws Exception {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Inactive");
        jdbc.update("UPDATE college SET status = 'INACTIVE' WHERE id = UUID_TO_BIN(?)", college.toString());

        join(student, college, "CS-106", "Civil")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void invalidBody_400() throws Exception {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Invalid");

        join(student, college, " ", "Civil").andExpect(status().isBadRequest());
        join(student, college, "X".repeat(51), "Civil").andExpect(status().isBadRequest());
        join(student, college, "CS-107", "D".repeat(101)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/memberships").with(signedIn(student, Role.USER)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rollNo\":\"CS-107\",\"department\":\"Civil\"}"))
                .andExpect(status().isBadRequest());

        assertThat(memberships.findByUserId(student)).isEmpty();
    }

    @Test
    void tpo_cannotRequest_403() throws Exception {
        UUID college = fixtures.college("TpoJoin");
        UUID tpo = fixtures.tpo(college);

        mockMvc.perform(post("/api/memberships").with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(body(college, "CS-108", "Civil")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void meWithoutMembership_404() throws Exception {
        mockMvc.perform(get("/api/memberships/me").with(signedIn(fixtures.student(), Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void me_withMembership_200() throws Exception {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Mine");
        join(student, college, "CS-109", "Civil").andExpect(status().isCreated());

        mockMvc.perform(get("/api/memberships/me").with(signedIn(student, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collegeId").value(college.toString()))
                .andExpect(jsonPath("$.rollNo").value("CS-109"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void authMe_afterRequest_showsCollegeAndPending() throws Exception {
        UUID student = fixtures.student();
        UUID college = fixtures.college("AuthMe");
        join(student, college, "CS-112", "Civil").andExpect(status().isCreated());

        mockMvc.perform(get("/api/auth/me").with(signedIn(student, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.college.id").value(college.toString()))
                .andExpect(jsonPath("$.college.name").value(fixtures.collegeName(college)))
                .andExpect(jsonPath("$.membershipStatus").value("PENDING"));
    }

    @Test
    void me_otherStudentsMembershipNeverVisible() throws Exception {
        UUID owner = fixtures.student();
        join(owner, fixtures.college("Owner"), "CS-110", "Civil").andExpect(status().isCreated());

        mockMvc.perform(get("/api/memberships/me").with(signedIn(fixtures.student(), Role.USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_withoutCsrf_403() throws Exception {
        UUID student = fixtures.student();

        mockMvc.perform(post("/api/memberships").with(signedIn(student, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixtures.college("Csrf"), "CS-111", "Civil")))
                .andExpect(status().isForbidden());

        assertThat(memberships.findByUserId(student)).isEmpty();
    }

    private ResultActions join(UUID student, UUID college, String rollNo, String department) throws Exception {
        return mockMvc.perform(post("/api/memberships").with(signedIn(student, Role.USER)).with(xsrf(mockMvc))
                .contentType(MediaType.APPLICATION_JSON).content(body(college, rollNo, department)));
    }

    private static String body(UUID college, String rollNo, String department) {
        return "{\"collegeId\":\"%s\",\"rollNo\":\"%s\",\"department\":\"%s\"}".formatted(college, rollNo, department);
    }
}
