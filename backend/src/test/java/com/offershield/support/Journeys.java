package com.offershield.support;

import com.jayway.jsonpath.JsonPath;
import com.offershield.analysis.SubmissionCreatedListener;
import com.offershield.auth.Role;
import com.offershield.registry.ReportSnapshotListener;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.submission.SubmissionStatusListener;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload → analysis → report pipeline on the embedded broker, for the P10 journey tests (05 §5 J3–J5): starts and
 * stops the three consumers, uploads a letter as a user, waits for its report and its registry snapshot. RDAP and DNS
 * answers come from {@link LookupStubs}; fictional letters, fake numbers and made-up domains only.
 */
public final class Journeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Instant OLD = Instant.now().minus(Duration.ofDays(4000));
    private static final List<String> CONSUMERS = List.of(SubmissionCreatedListener.ID, SubmissionStatusListener.ID,
            ReportSnapshotListener.ID);

    private final MockMvc mockMvc;
    private final KafkaListenerEndpointRegistry registry;
    private final ReportSnapshotService snapshots;

    public Journeys(ApplicationContext context, MockMvc mockMvc) {
        this.mockMvc = mockMvc;
        this.registry = context.getBean(KafkaListenerEndpointRegistry.class);
        this.snapshots = context.getBean(ReportSnapshotService.class);
    }

    public void startConsumers() {
        CONSUMERS.forEach(id -> registry.getListenerContainer(id).start());
    }

    public void stopConsumers() {
        CONSUMERS.forEach(id -> registry.getListenerContainer(id).stop());
    }

    /** Uploads pasted text as {@code user} and waits until it is analysed and its registry snapshot is stored. */
    public UUID analyse(UUID user, String text, String senderEmail) throws Exception {
        var request = multipart("/api/submissions").param("text", text)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(signedIn(user, Role.USER)).with(xsrf(mockMvc));
        if (senderEmail != null) {
            request.param("senderEmail", senderEmail);
        }
        String body = mockMvc.perform(request).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.submissionId"));
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String submission = mockMvc.perform(get("/api/submissions/{id}", id).with(signedIn(user, Role.USER)))
                    .andReturn().getResponse().getContentAsString();
            return !"QUEUED".equals(JsonPath.read(submission, "$.status"));
        });
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> snapshots.find(id).isPresent());
        return id;
    }

    public String report(UUID owner, UUID submission) throws Exception {
        return mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    public static Map<String, Object> signal(String report, String code) {
        List<Map<String, Object>> signals = JsonPath.read(report, "$.signals[?(@.code == '" + code + "')]");
        assertThat(signals).as(code).hasSize(1);
        return signals.get(0);
    }

    /** An old domain with MX and DMARC, so C5/C6 add nothing. */
    public static String healthyDomain(String prefix) {
        String domain = prefix + "-" + token(8) + ".com";
        LookupStubs.RDAP.registered(domain, OLD);
        LookupStubs.DNS.mx(domain, "mx." + domain).txt("_dmarc." + domain, "v=DMARC1; p=reject");
        return domain;
    }

    /** A fake Indian mobile number +91 99999 05xxx–09xxx (05 §4), random so tests never share markers. */
    public static String phone() {
        return "+91 99999 0" + (5000 + RANDOM.nextInt(5000));
    }

    private static String token(int length) {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < length; i++) {
            token.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return token.toString();
    }
}
