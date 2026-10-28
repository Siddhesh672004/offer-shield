package com.offershield.submission;

import com.offershield.auth.AppUser;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.College;
import com.offershield.college.CollegeRepository;
import com.offershield.membership.Membership;
import com.offershield.membership.MembershipRepository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Fake users, colleges and upload requests for submission integration tests. Every value is made up. */
final class SubmissionFixtures {

    /** A fictional scam-style letter with planted markers (S-11/S-12): none may ever appear outside the cipher. */
    static final String MARKER = "ZEBRA-LETTER-MARKER-P5";
    static final String LETTER = "Dear Candidate, congratulations on your selection as Junior Analyst at Example "
            + "Fictional Corp. Pay the refundable onboarding deposit today. Contact hr.desk@fictional-hiring.example. "
            + MARKER;
    static final String PHONE = "+91 99999 01234";
    static final String UPI = "marker.p5@upi";
    static final String SENDER = "recruiter.p5@fictional-hiring.example";

    private final OidcUserProvisioningService provisioning;
    private final CollegeRepository colleges;
    private final MembershipRepository memberships;
    private final MockMvc mockMvc;

    SubmissionFixtures(OidcUserProvisioningService provisioning, CollegeRepository colleges,
                       MembershipRepository memberships, MockMvc mockMvc) {
        this.provisioning = provisioning;
        this.colleges = colleges;
        this.memberships = memberships;
        this.mockMvc = mockMvc;
    }

    UUID user() {
        String key = UUID.randomUUID().toString();
        return provisioning.provision(new OidcIdentity("sub-p5-" + key, "student-p5-" + key + "@example.com", true,
                "Test Student")).userId();
    }

    /** A user whose database role is {@code role} (the session role is refreshed from the database on each request). */
    UUID userWithRole(AppUserRepository users, Role role) {
        UUID userId = user();
        AppUser user = users.findById(userId).orElseThrow();
        if (role == Role.TPO) {
            user.assignTpoCollege(colleges.saveAndFlush(College.create("P5 TPO College " + UUID.randomUUID(), "Pune")).getId());
        }
        user.changeRole(role);
        users.saveAndFlush(user);
        return userId;
    }

    /** A user whose join request was approved, so their uploads are on-campus. */
    UUID approvedMember() {
        UUID userId = user();
        UUID collegeId = colleges.saveAndFlush(College.create("P5 College " + UUID.randomUUID(), "Pune")).getId();
        Membership membership = Membership.request(userId, collegeId, "R-" + userId.toString().substring(0, 8), "CSE");
        membership.approve(userId, Instant.now());
        memberships.saveAndFlush(membership);
        return userId;
    }

    /** A signed-in USER's multipart upload with CSRF and an Idempotency-Key. */
    MockMultipartHttpServletRequestBuilder upload(UUID userId, String idempotencyKey) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/submissions");
        builder.with(signedIn(userId, Role.USER)).with(xsrf(mockMvc));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder;
    }

    static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("file", "offer.pdf", MediaType.APPLICATION_PDF_VALUE, bytes);
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    static byte[] idBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static long count(JdbcTemplate jdbc, UUID ownerUserId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM submission WHERE owner_user_id = ?", Long.class,
                idBytes(ownerUserId));
        return count == null ? 0 : count;
    }
}
