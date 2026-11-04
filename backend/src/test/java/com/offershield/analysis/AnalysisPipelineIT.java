package com.offershield.analysis;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.jayway.jsonpath.JsonPath;
import com.offershield.analysis.checks.Letters;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.common.events.EventHeaders;
import com.offershield.common.events.Topics;
import com.offershield.support.IntegrationTest;
import com.offershield.support.KafkaRecords;
import com.offershield.support.LogCapture;
import com.offershield.support.AiStubs;
import com.offershield.support.GroqStub;
import com.offershield.support.LookupStubs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
 * P6 end to end on the embedded broker (PRD §6.2, D-20, D-28): upload → {@code submission.created} → analysis (checks,
 * RDAP/DNS stubs, scoring, report) → {@code analysis.completed} → {@code submission-status} → READY / PARTIAL → the
 * owner reads the report (P7: the AI is the local Groq stub, which gets only masked text). Also S-05 (SSRF senders
 * never reach RDAP or DNS) and F-05 (malformed JSON on {@code submission.created} lands in its DLT without retries).
 * Fake letters only.
 */
class AnalysisPipelineIT extends IntegrationTest {

    private static final String PHONE = "+91 99999 01234";
    private static final String UPI = "marker.p6@upi";
    private static final String MARKER = "ZEBRA-MARKER-P6";

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private UUID user;

    @BeforeEach
    void startConsumers() {
        user = user();
        registry.getListenerContainer(SubmissionCreatedListener.ID).start();
        registry.getListenerContainer(com.offershield.submission.SubmissionStatusListener.ID).start();
    }

    @AfterEach
    void stopConsumers() {
        registry.getListenerContainer(SubmissionCreatedListener.ID).stop();
        registry.getListenerContainer(com.offershield.submission.SubmissionStatusListener.ID).stop();
    }

    @Test
    void depositLetter_highWithC1Evidence_statusReady_noPiiInLogsOrEvent() throws Exception {
        LookupStubs.RDAP.registered("young-firm-p6.com", Instant.now().minus(Duration.ofDays(30)));
        String letter = Letters.fixture(Letters.DEPOSIT_SCAM) + "\nReference " + MARKER + ". Alternate number "
                + PHONE + ", alternate UPI " + UPI + "\n";
        String requestId;
        UUID id;
        try (LogCapture logs = LogCapture.root()) {
            MockHttpServletResponse response = mockMvc.perform(upload(letter).param("senderEmail", "hr@young-firm-p6.com")
                            .param("recruiterPhone", PHONE).param("upiId", UPI))
                    .andExpect(status().isAccepted()).andReturn().getResponse();
            requestId = response.getHeader("X-Request-Id");
            id = UUID.fromString(JsonPath.read(response.getContentAsString(), "$.submissionId"));

            assertThat(awaitFinalStatus(id)).isEqualTo("READY");
            assertNoPii(logs.events());
        }

        String report = report(id);
        assertThat((String) JsonPath.read(report, "$.band")).isEqualTo("HIGH");
        assertThat((Boolean) JsonPath.read(report, "$.hardOverride")).isTrue();
        assertThat((Boolean) JsonPath.read(report, "$.partial")).isFalse();
        assertThat((Boolean) JsonPath.read(report, "$.aiUsed")).as("the AI (Groq stub) answered").isTrue();
        // P7 / D-07: the letter reached the AI only masked: no planted phone or UPI ID in the bytes sent.
        List<String> aiBodies = AiStubs.GROQ.requests().stream().map(GroqStub.Request::body)
                .filter(body -> body.contains(MARKER)).toList();
        assertThat(aiBodies).hasSize(1);
        assertThat(aiBodies.get(0)).contains("[PHONE_", "[UPI_").doesNotContain("99999").doesNotContain(UPI)
                .doesNotContain("test.deposit@upi");
        assertThat((String) JsonPath.read(report, "$.senderDomain")).isEqualTo("young-firm-p6.com");
        Map<String, Object> c1 = signal(report, "C1");
        assertThat(c1.get("outcome")).isEqualTo("TRIGGERED");
        assertThat(c1.get("sourceKind")).isEqualTo(1);
        assertThat((String) c1.get("evidence")).contains("refundable security deposit");
        Map<String, Object> c5 = signal(report, "C5");
        assertThat(c5.get("outcome")).isEqualTo("TRIGGERED");
        assertThat(c5.get("points")).isEqualTo(20);
        assertThat(c5.get("sourceKind")).isEqualTo(2);
        Map<String, Object> c6 = signal(report, "C6");
        assertThat(c6.get("points")).isEqualTo(20); // the DNS stub has neither MX nor DMARC for it

        ConsumerRecord<String, byte[]> completed = KafkaRecords.readAll(Topics.ANALYSIS_COMPLETED, id.toString(), 1,
                Duration.ofSeconds(15)).get(0);
        String json = KafkaRecords.value(completed);
        assertThat(KafkaRecords.header(completed, EventHeaders.EVENT_ID))
                .isEqualTo(UUID.nameUUIDFromBytes(("analysis.completed:" + id).getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(KafkaRecords.header(completed, EventHeaders.REQUEST_ID)).isEqualTo(requestId);
        assertThat((String) JsonPath.read(json, "$.payload.outcome")).isEqualTo("READY");
        assertThat((String) JsonPath.read(json, "$.payload.band")).isEqualTo("HIGH");
        assertThat(json).doesNotContain(MARKER).doesNotContain("99999").doesNotContain(UPI);
    }

    @Test
    void tldWithoutRdapServer_partialAndNamed() throws Exception {
        UUID id = submit("Dear Candidate, welcome to the team. Your joining date will follow.", "hr@acme-p6.org");

        assertThat(awaitFinalStatus(id)).isEqualTo("PARTIAL");
        String report = report(id);
        assertThat((Boolean) JsonPath.read(report, "$.partial")).isTrue();
        List<String> unavailable = JsonPath.read(report, "$.unavailableChecks");
        assertThat(unavailable).containsExactly("C5");
        assertThat(signal(report, "C5").get("outcome")).isEqualTo("UNAVAILABLE");
    }

    @Test
    void ssrfSenders_noOutboundCall_c5c6NotRun() throws Exception {
        // S-05: a typed IPv6 literal is refused at the door (bean validation); nothing is stored or looked up.
        mockMvc.perform(upload("Dear Candidate, welcome to the team.").param("senderEmail", "x@[::1]"))
                .andExpect(status().isBadRequest());
        // A typed IPv4 literal is accepted as an address, but the SSRF guard keeps it away from RDAP and DNS.
        UUID ipv4 = submit("Dear Candidate, welcome to the team.", "x@127.0.0.1");
        // Literals written in the letter never become the sender domain.
        UUID inLetter = submit("Dear Candidate, write to x@[::1] or x@169.254.169.254 to accept.", null);

        for (UUID id : List.of(ipv4, inLetter)) {
            assertThat(awaitFinalStatus(id)).isEqualTo("READY");
            String report = report(id);
            assertThat(signal(report, "C5").get("outcome")).isEqualTo("NOT_APPLICABLE");
            assertThat(signal(report, "C6").get("outcome")).isEqualTo("NOT_APPLICABLE");
            assertThat((Object) JsonPath.read(report, "$.senderDomain")).isNull();
        }
        assertThat(LookupStubs.RDAP.requests()).noneMatch(path -> path.contains("127.0.0.1") || path.contains("::1")
                || path.contains("169.254"));
        assertThat(LookupStubs.DNS.queries()).noneMatch(query -> query.contains("127.0.0.1") || query.contains("::1")
                || query.contains("169.254"));
    }

    @Test
    void malformedSubmissionCreated_deadLetteredWithoutRetries() {
        String key = UUID.randomUUID().toString();
        byte[] poison = "{\"eventId\": oops ZEBRA-F05".getBytes(StandardCharsets.UTF_8);

        try (LogCapture logs = LogCapture.root()) {
            KafkaRecords.sendRaw(Topics.SUBMISSION_CREATED, key, poison);
            List<ConsumerRecord<String, byte[]>> dead = KafkaRecords.readAll(Topics.dlt(Topics.SUBMISSION_CREATED), key, 1,
                    Duration.ofSeconds(30));

            assertThat(dead).hasSize(1);
            ConsumerRecord<String, byte[]> record = dead.get(0);
            assertThat(record.value()).isEqualTo(poison);
            assertThat(KafkaRecords.header(record, "kafka_dlt-exception-fqcn")).contains("DeserializationException");
            assertThat(KafkaRecords.header(record, "kafka_dlt-original-topic")).isEqualTo(Topics.SUBMISSION_CREATED);
            assertThat(KafkaRecords.header(record, "kafka_dlt-exception-message")).isNull();
            // No retries: three retries would wait 1 + 2 + 4 s before dead-lettering.
            long originalTimestamp = ByteBuffer.wrap(
                    record.headers().lastHeader("kafka_dlt-original-timestamp").value()).getLong();
            assertThat(record.timestamp() - originalTimestamp).isBetween(0L, 5_000L);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(logs.events())
                    .anySatisfy(event -> {
                        assertThat(event.getFormattedMessage()).contains("event dead-lettered topic=submission.created")
                                .contains("error=StreamReadException");
                        assertThat(event.getMDCPropertyMap().get("requestId")).isNotBlank(); // D-27
                    }));
            assertThat(logs.events()).noneSatisfy(event -> assertThat(line(event)).contains("ZEBRA-F05"));
        }
    }

    @Test
    void badGroqKey_reportArrivesPartialAiUnavailable() throws Exception {
        // F-01 drill against the stub: Groq answers 401 (invalid key) for every model.
        AiStubs.GROQ.respond(request -> GroqStub.Reply.status(401, "{\"error\":{\"message\":\"Invalid API Key\","
                + "\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"));
        try (LogCapture logs = LogCapture.root()) {
            UUID id = submit(Letters.fixture(Letters.DEPOSIT_SCAM) + "\nReference F01-DRILL.\n", null);

            assertThat(awaitFinalStatus(id)).isEqualTo("PARTIAL");
            String report = report(id);
            assertThat((Boolean) JsonPath.read(report, "$.aiUsed")).isFalse();
            assertThat((Boolean) JsonPath.read(report, "$.partial")).isTrue();
            assertThat((String) JsonPath.read(report, "$.band")).isEqualTo("HIGH");
            assertThat(signal(report, "C1").get("outcome")).isEqualTo("TRIGGERED");
            assertThat(AiStubs.GROQ.requests().stream().filter(request -> request.body().contains("F01-DRILL")).count())
                    .as("a rejected key stops the chain: one call, not one per model").isEqualTo(1);
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel().toString()).isEqualTo("WARN");
                assertThat(event.getFormattedMessage()).contains("ai key rejected (status=401)");
            });
            assertThat(logs.events()).noneSatisfy(event -> assertThat(line(event)).contains(AiStubs.TEST_KEY));
        } finally {
            AiStubs.GROQ.reset();
        }
    }

    private UUID submit(String text, String senderEmail) throws Exception {
        MockMultipartHttpServletRequestBuilder request = upload(text);
        if (senderEmail != null) {
            request.param("senderEmail", senderEmail);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }

    private MockMultipartHttpServletRequestBuilder upload(String text) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/submissions");
        builder.param("text", text).header("Idempotency-Key", UUID.randomUUID().toString())
                .with(signedIn(user, Role.USER)).with(xsrf(mockMvc));
        return builder;
    }

    private String awaitFinalStatus(UUID id) {
        String[] status = new String[1];
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String body = mockMvc.perform(get("/api/submissions/{id}", id).with(signedIn(user, Role.USER)))
                    .andReturn().getResponse().getContentAsString();
            status[0] = JsonPath.read(body, "$.status");
            return !"QUEUED".equals(status[0]);
        });
        return status[0];
    }

    private String report(UUID id) throws Exception {
        return mockMvc.perform(get("/api/reports/{id}", id).with(signedIn(user, Role.USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Object> signal(String report, String code) {
        List<Map<String, Object>> signals = JsonPath.read(report, "$.signals[?(@.code == '" + code + "')]");
        assertThat(signals).as(code).hasSize(1);
        return signals.get(0);
    }

    private UUID user() {
        String key = UUID.randomUUID().toString();
        return provisioning.provision(new OidcIdentity("sub-p6-" + key, "student-p6-" + key + "@example.com", true,
                "Test Student")).userId();
    }

    private static void assertNoPii(List<ILoggingEvent> events) {
        assertThat(events).isNotEmpty().allSatisfy(event -> assertThat(line(event))
                .doesNotContain(MARKER).doesNotContain("99999").doesNotContain(UPI)
                .doesNotContain("young-firm-p6.com").doesNotContain("refundable security deposit"));
    }

    private static String line(ILoggingEvent event) {
        StringBuilder line = new StringBuilder(event.getFormattedMessage()).append(' ').append(event.getMDCPropertyMap());
        for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            line.append(' ').append(proxy.getMessage());
        }
        return line.toString();
    }
}
