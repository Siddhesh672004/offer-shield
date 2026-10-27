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

import java.util.List;
import java.util.UUID;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PRD §14.3 / §18 / S-03 (submission part): only the owner can read a submission; anyone else gets a 404. */
class SubmissionAccessTest extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;

    private SubmissionFixtures fixtures;
    private UUID owner;
    private UUID stranger;
    private String ownersSubmission;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        owner = fixtures.user();
        stranger = fixtures.user();
        ownersSubmission = submit(owner, SubmissionFixtures.LETTER);
    }

    @Test
    void otherUsersSubmission_404() throws Exception {
        String foreign = mockMvc.perform(get("/api/submissions/{id}", ownersSubmission).with(signedIn(stranger, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String random = mockMvc.perform(get("/api/submissions/{id}", UUID.randomUUID()).with(signedIn(stranger, Role.USER)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Same answer for "someone else's" and "does not exist" (apart from the request-specific fields).
        assertThat(withoutRequestFields(foreign)).isEqualTo(withoutRequestFields(random));
        assertThat(foreign).doesNotContain(owner.toString());
    }

    @Test
    void tpoOrAdmin_cannotReadAStudentsSubmission_404() throws Exception {
        mockMvc.perform(get("/api/submissions/{id}", ownersSubmission).with(signedIn(fixtures.userWithRole(users, Role.TPO), Role.TPO)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/submissions/{id}", ownersSubmission).with(signedIn(fixtures.userWithRole(users, Role.ADMIN), Role.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void own_200() throws Exception {
        mockMvc.perform(get("/api/submissions/{id}", ownersSubmission).with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownersSubmission))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.sourceType").value("TEXT"))
                .andExpect(jsonPath("$.onCampus").value(false))
                .andExpect(jsonPath("$.pdfStored").value(false))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void approvedMember_onCampus() throws Exception {
        UUID member = fixtures.approvedMember();
        String id = submit(member, SubmissionFixtures.LETTER);

        mockMvc.perform(get("/api/submissions/{id}", id).with(signedIn(member, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onCampus").value(true));
    }

    @Test
    void list_onlyOwn() throws Exception {
        String second = submit(owner, SubmissionFixtures.LETTER + " Second letter.");
        String strangers = submit(stranger, SubmissionFixtures.LETTER);

        String body = mockMvc.perform(get("/api/submissions").with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$[*].id");
        assertThat(ids).containsExactly(second, ownersSubmission).doesNotContain(strangers);
    }

    @Test
    void list_emptyForSomeoneWithoutSubmissions() throws Exception {
        mockMvc.perform(get("/api/submissions").with(signedIn(fixtures.userWithRole(users, Role.TPO), Role.TPO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(get("/api/submissions/{id}", ownersSubmission))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    private String submit(UUID user, String text) throws Exception {
        String body = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", text))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.submissionId");
    }

    private static String withoutRequestFields(String problemJson) {
        return problemJson.replaceAll("\"(instance|requestId)\":\"[^\"]*\",?", "");
    }
}
