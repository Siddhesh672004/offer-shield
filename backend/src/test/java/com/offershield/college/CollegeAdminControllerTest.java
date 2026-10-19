package com.offershield.college;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.AppUser;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.ProvisionedUser;
import com.offershield.auth.Role;
import com.offershield.auth.TpoAssignment;
import com.offershield.auth.TpoAssignmentRepository;
import com.offershield.common.audit.AuditLog;
import com.offershield.common.audit.AuditLogRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollegeAdminControllerTest extends IntegrationTest {

    /** Unique per test, so rows left by other tests in the shared database never collide. */
    private final String token = "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @Autowired
    private OidcUserProvisioningService provisioningService;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private TpoAssignmentRepository assignments;
    @Autowired
    private AuditLogRepository auditLogs;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        // The admin needs an app_user row: tpo_assignment.assigned_by references it.
        adminId = provision(token + ".admin@example.com", true).userId();
    }

    @Test
    void create_admin_201TrimmedAndActive() throws Exception {
        mockMvc.perform(post("/api/admin/colleges").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collegeJson("  " + token + " Institute of Technology  ", "  Pune ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value(token + " Institute of Technology"))
                .andExpect(jsonPath("$.city").value("Pune"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void create_blankCity_cityNull() throws Exception {
        mockMvc.perform(post("/api/admin/colleges").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collegeJson(token + " No City College", "   ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").doesNotExist());
    }

    @Test
    void create_nameShorterThan2AfterTrim_400() throws Exception {
        mockMvc.perform(post("/api/admin/colleges").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collegeJson("  x  ", "Pune")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void duplicateCollege_409() throws Exception {
        createCollege(token + " Duplicate College");

        mockMvc.perform(post("/api/admin/colleges").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collegeJson(" " + (token + " DUPLICATE college").toLowerCase(Locale.ROOT) + " ", "Pune")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void nonAdmin_403() throws Exception {
        UUID collegeId = createCollege(token + " Guarded College");

        for (Role role : List.of(Role.USER, Role.TPO)) {
            mockMvc.perform(post("/api/admin/colleges").with(signedIn(role)).with(xsrf(mockMvc))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(collegeJson(token + " Sneaky College " + role, "Pune")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
            mockMvc.perform(get("/api/admin/colleges").with(signedIn(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/colleges/{id}/tpos", collegeId).with(signedIn(role)).with(xsrf(mockMvc))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailJson(token + ".sneaky@example.com")))
                    .andExpect(status().isForbidden());
        }
        assertThat(assignments.findByEmail(token.toLowerCase(Locale.ROOT) + ".sneaky@example.com")).isEmpty();
    }

    @Test
    void assignExistingUser_promoted() throws Exception {
        UUID collegeId = createCollege(token + " Promote College");
        String email = token.toLowerCase(Locale.ROOT) + ".tpo@example.com";
        ProvisionedUser user = provision(email, true);

        mockMvc.perform(post("/api/admin/colleges/{id}/tpos", collegeId).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailJson("  " + email.toUpperCase(Locale.ROOT) + " ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        AppUser promoted = users.findById(user.userId()).orElseThrow();
        assertThat(promoted.getRole()).isEqualTo(Role.TPO);
        assertThat(promoted.getTpoCollegeId()).isEqualTo(collegeId);
        assertThat(assignments.findByEmail(email)).isEmpty();
    }

    @Test
    void unverifiedExistingUser_notPromoted_pending() throws Exception {
        UUID collegeId = createCollege(token + " Unverified College");
        String email = token.toLowerCase(Locale.ROOT) + ".unverified@example.com";
        ProvisionedUser user = provision(email, false);

        mockMvc.perform(post("/api/admin/colleges/{id}/tpos", collegeId).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailJson(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        AppUser unchanged = users.findById(user.userId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(Role.USER);
        assertThat(unchanged.getTpoCollegeId()).isNull();
        assertThat(assignments.findByEmail(email)).get().extracting(TpoAssignment::getCollegeId).isEqualTo(collegeId);
    }

    @Test
    void assignNewEmail_pending() throws Exception {
        UUID collegeId = createCollege(token + " Pending College");
        String email = token.toLowerCase(Locale.ROOT) + ".future.tpo@example.com";

        mockMvc.perform(post("/api/admin/colleges/{id}/tpos", collegeId).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailJson(" " + email.toUpperCase(Locale.ROOT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("PENDING"));

        TpoAssignment assignment = assignments.findByEmail(email).orElseThrow();
        assertThat(assignment.getEmail()).isEqualTo(email);
        assertThat(assignment.getCollegeId()).isEqualTo(collegeId);
        assertThat(assignment.getAssignedBy()).isEqualTo(adminId);
    }

    @Test
    void assignPendingEmailAgain_movedToNewCollege() throws Exception {
        UUID first = createCollege(token + " First College");
        UUID second = createCollege(token + " Second College");
        String email = token.toLowerCase(Locale.ROOT) + ".moving@example.com";

        assignTpo(first, email);
        assignTpo(second, email);

        assertThat(assignments.findAll()).filteredOn(a -> a.getEmail().equals(email))
                .singleElement().extracting(TpoAssignment::getCollegeId).isEqualTo(second);
    }

    @Test
    void assign_invalidEmail_400() throws Exception {
        UUID collegeId = createCollege(token + " Invalid Email College");

        mockMvc.perform(post("/api/admin/colleges/{id}/tpos", collegeId).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailJson("not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownCollege_404() throws Exception {
        String email = token.toLowerCase(Locale.ROOT) + ".nowhere@example.com";

        mockMvc.perform(post("/api/admin/colleges/{id}/tpos", UUID.randomUUID()).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailJson(email)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        assertThat(assignments.findByEmail(email)).isEmpty();
    }

    @Test
    void audited() throws Exception {
        UUID collegeId = createCollege(token + " Audited College");
        String verifiedEmail = token.toLowerCase(Locale.ROOT) + ".audited.tpo@example.com";
        ProvisionedUser verified = provision(verifiedEmail, true);

        assignTpo(collegeId, verifiedEmail);
        assignTpo(collegeId, token.toLowerCase(Locale.ROOT) + ".audited.pending@example.com");

        assertThat(auditLogs.findAll()).filteredOn(log -> log.getActorId().equals(adminId))
                .extracting(AuditLog::getAction, AuditLog::getTargetId)
                .containsExactlyInAnyOrder(
                        tuple("COLLEGE_CREATED", collegeId),
                        tuple("TPO_ASSIGNED", verified.userId()),
                        tuple("TPO_ASSIGNMENT_CREATED", collegeId));
    }

    @Test
    void adminList_showsTpoAndPendingEmails() throws Exception {
        UUID staffed = createCollege(token + " A Staffed College");
        UUID empty = createCollege(token + " B Empty College");
        String tpoEmail = token.toLowerCase(Locale.ROOT) + ".listed.tpo@example.com";
        String pendingEmail = token.toLowerCase(Locale.ROOT) + ".listed.pending@example.com";
        provision(tpoEmail, true);
        assignTpo(staffed, tpoEmail);
        assignTpo(staffed, pendingEmail);

        String body = mockMvc.perform(get("/api/admin/colleges").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> rows = JsonPath.read(body, "$");
        List<String> ids = rows.stream().map(row -> (String) row.get("id")).toList();
        assertThat(ids).contains(staffed.toString(), empty.toString());
        assertThat(ids.indexOf(staffed.toString())).isLessThan(ids.indexOf(empty.toString()));

        Map<String, Object> staffedRow = rows.get(ids.indexOf(staffed.toString()));
        assertThat(staffedRow).containsEntry("name", token + " A Staffed College")
                .containsEntry("city", "Pune")
                .containsEntry("status", "ACTIVE")
                .containsEntry("tpoEmails", List.of(tpoEmail))
                .containsEntry("pendingTpoEmails", List.of(pendingEmail));
        assertThat(rows.get(ids.indexOf(empty.toString())))
                .containsEntry("tpoEmails", List.of())
                .containsEntry("pendingTpoEmails", List.of());
    }

    private RequestPostProcessor admin() {
        return signedIn(adminId, Role.ADMIN);
    }

    private ProvisionedUser provision(String email, boolean emailVerified) {
        return provisioningService.provision(
                new OidcIdentity("sub-" + UUID.randomUUID(), email, emailVerified, "Test Person"));
    }

    private UUID createCollege(String name) throws Exception {
        String body = mockMvc.perform(post("/api/admin/colleges").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collegeJson(name, "Pune")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void assignTpo(UUID collegeId, String email) throws Exception {
        mockMvc.perform(post("/api/admin/colleges/{id}/tpos", collegeId).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailJson(email)))
                .andExpect(status().isOk());
    }

    private static String collegeJson(String name, String city) {
        return """
                {"name": "%s", "city": "%s"}""".formatted(name, city);
    }

    private static String emailJson(String email) {
        return """
                {"email": "%s"}""".formatted(email);
    }
}
