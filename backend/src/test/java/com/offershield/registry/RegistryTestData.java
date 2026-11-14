package com.offershield.registry;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.common.events.AnalysisCompletedEvent;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fake users, submissions and report snapshots for registry integration tests. Every key is random (the database is
 * shared by all tests of the JVM, so no test can see another's markers), and every letter is made up.
 */
final class RegistryTestData {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTER = "Dear Candidate, this is a fictional letter used only by the registry "
            + "integration tests.";

    private final OidcUserProvisioningService provisioning;
    private final MockMvc mockMvc;
    private final ReportSnapshotService snapshots;

    RegistryTestData(OidcUserProvisioningService provisioning, MockMvc mockMvc, ReportSnapshotService snapshots) {
        this.provisioning = provisioning;
        this.mockMvc = mockMvc;
        this.snapshots = snapshots;
    }

    UUID user() {
        String key = UUID.randomUUID().toString();
        return provisioning.provision(new OidcIdentity("sub-p9-" + key, "user-p9-" + key + "@example.com", true,
                "Test User")).userId();
    }

    /** A real (QUEUED) submission of a new user, so the snapshot's foreign key holds. */
    UUID submission(UUID owner) throws Exception {
        String body = mockMvc.perform(multipart("/api/submissions")
                        .param("text", LETTER)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(signedIn(owner, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }

    /** A stored snapshot of a new submission of {@code owner} with these keys; returns the submission ID. */
    UUID snapshot(UUID owner, String senderDomain, List<String> phoneHmacs, List<String> upiHmacs, Long simhash)
            throws Exception {
        UUID submission = submission(owner);
        snapshots.store(UUID.randomUUID(), payload(submission, owner, senderDomain, phoneHmacs, upiHmacs, simhash),
                Instant.now());
        return submission;
    }

    static AnalysisCompletedEvent.Payload payload(UUID submission, UUID owner, String senderDomain,
                                                  List<String> phoneHmacs, List<String> upiHmacs, Long simhash) {
        return new AnalysisCompletedEvent.Payload(submission, owner, null, AnalysisCompletedEvent.READY, 30, "HIGH",
                true, List.of(), List.of(new AnalysisCompletedEvent.Signal("C1", "TRIGGERED", 0, true,
                "The letter asks you to pay money.", "pay Rs. 5,000 refundable deposit", 1, false)),
                senderDomain, phoneHmacs, upiHmacs, simhash, true);
    }

    /** A random 64-hex-digit key, standing in for an HMAC of a fake phone number or UPI ID. */
    static String key() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A random registrable {@code .com} domain (never looked up: snapshots skip the network). */
    static String domain() {
        return "scam-p9-" + HexFormat.of().formatHex(new byte[]{(byte) RANDOM.nextInt(), (byte) RANDOM.nextInt(),
                (byte) RANDOM.nextInt(), (byte) RANDOM.nextInt()}) + ".com";
    }

    /** A random fingerprint with the top bit set (a negative long), to prove BIT_COUNT works on all 64 bits. */
    static long simhash() {
        return RANDOM.nextLong() | Long.MIN_VALUE;
    }
}
