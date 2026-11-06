package com.offershield.ai;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.offershield.support.GroqStub;
import com.offershield.support.GroqStub.Reply;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §8.4 / §18 (P7-01): the Groq model chain against a local stub speaking the OpenAI-compatible API. A 429 rests
 * the model (retry-after seconds, else the duration reset headers of V-06/I-27, else 60 s) and moves on; 5xx, timeouts,
 * refused connections, unreadable or invalid answers and unknown models move on; a rejected key stops the chain; when
 * every model fails the answer is empty (the report then says "AI unavailable").
 */
class LlmChainTest {

    private static final String KEY = "test-key-SECRET-K9"; // gitleaks:allow (fake key: tests that keys never reach logs)
    private static final String A = "model-a";
    private static final String B = "model-b";
    private static final String C = "model-c";
    private static final String GOOD = "{\"ok\":true}";
    private static final Function<String, Optional<String>> PARSER =
            content -> GOOD.equals(content) ? Optional.of(content) : Optional.empty();
    private static final ChatRequest REQUEST = new ChatRequest("extract", "SYSTEM-PROMPT-MARKER JSON only",
            "USER-LETTER-MARKER Dear Candidate", 900);

    private final GroqStub stub = GroqStub.start();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void rateLimited_next() {
        stub.replyFor(A, Reply.rateLimited(Map.of("retry-after", "30"))).replyFor(B, Reply.content(GOOD));

        Optional<LlmAnswer<String>> answer = chain(A, B).complete(REQUEST, PARSER);

        assertThat(answer).hasValueSatisfying(value -> {
            assertThat(value.model()).isEqualTo(B);
            assertThat(value.provider()).isEqualTo("groq");
            assertThat(value.value()).isEqualTo(GOOD);
        });
        assertThat(stub.hits(A)).isEqualTo(1);
        assertThat(stub.hits(B)).isEqualTo(1);
    }

    @Test
    void retryAfterHonoured() {
        stub.replyFor(A, Reply.rateLimited(Map.of("retry-after", "30"))).replyFor(B, Reply.content(GOOD));
        LlmChain chain = chain(A, B);
        chain.complete(REQUEST, PARSER);

        clock.advance(Duration.ofSeconds(29));
        assertThat(chain.complete(REQUEST, PARSER)).hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(B));
        assertThat(stub.hits(A)).as("model A rests until its retry-after has passed").isEqualTo(1);

        stub.replyFor(A, Reply.content(GOOD));
        clock.advance(Duration.ofSeconds(2));
        assertThat(chain.complete(REQUEST, PARSER)).hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(A));
        assertThat(stub.hits(A)).isEqualTo(2);
    }

    @Test
    void durationHeaderParsed() {
        // V-06 / I-27: no retry-after; the reset headers are Go-style durations. The daily request limit is used up.
        stub.replyFor(A, Reply.rateLimited(Map.of(
                "x-ratelimit-remaining-requests", "0", "x-ratelimit-reset-requests", "2m59.56s",
                "x-ratelimit-remaining-tokens", "7000", "x-ratelimit-reset-tokens", "7.66s")))
                .replyFor(B, Reply.content(GOOD));
        LlmChain chain = chain(A, B);
        chain.complete(REQUEST, PARSER);

        clock.advance(Duration.ofSeconds(179));
        chain.complete(REQUEST, PARSER);
        assertThat(stub.hits(A)).as("still resting after 179 s of 179.56 s").isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        chain.complete(REQUEST, PARSER);
        assertThat(stub.hits(A)).isEqualTo(2);
    }

    @Test
    void durationHeaders_tokensExhausted_shortRest() {
        stub.replyFor(A, Reply.rateLimited(Map.of(
                "x-ratelimit-remaining-requests", "950", "x-ratelimit-reset-requests", "14m24s",
                "x-ratelimit-remaining-tokens", "0", "x-ratelimit-reset-tokens", "7.66s")))
                .replyFor(B, Reply.content(GOOD));
        LlmChain chain = chain(A, B);
        chain.complete(REQUEST, PARSER);

        clock.advance(Duration.ofSeconds(8));
        chain.complete(REQUEST, PARSER);

        assertThat(stub.hits(A)).as("only the per-minute token limit was hit: back after 7.66 s").isEqualTo(2);
    }

    @Test
    void rateLimited_noHeaders_restsSixtySeconds() {
        stub.replyFor(A, Reply.rateLimited(Map.of())).replyFor(B, Reply.content(GOOD));
        LlmChain chain = chain(A, B);
        chain.complete(REQUEST, PARSER);

        clock.advance(Duration.ofSeconds(59));
        chain.complete(REQUEST, PARSER);
        assertThat(stub.hits(A)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        chain.complete(REQUEST, PARSER);
        assertThat(stub.hits(A)).isEqualTo(2);
    }

    @Test
    void readTimeout_next() {
        stub.replyFor(A, Reply.content(GOOD).delayed(Duration.ofSeconds(3))).replyFor(B, Reply.content(GOOD));

        long start = System.nanoTime();
        Optional<LlmAnswer<String>> answer = chain(Duration.ofMillis(400), A, B).complete(REQUEST, PARSER);

        assertThat(answer).hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(B));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void connectRefused_next() throws IOException {
        GroqClient refused = client(URI.create("http://127.0.0.1:" + freePort() + "/openai/v1"), Duration.ofSeconds(2));
        GroqClient working = client(URI.create(stub.baseUrl()), Duration.ofSeconds(2));
        stub.replyFor(B, Reply.content(GOOD));
        ChatProvider routing = new ChatProvider() {
            @Override
            public String name() {
                return "groq";
            }

            @Override
            public ProviderReply complete(String model, ChatRequest request) {
                return (A.equals(model) ? refused : working).complete(model, request);
            }
        };

        Optional<LlmAnswer<String>> answer = new LlmChain(properties(Duration.ofSeconds(2), A, B), routing, clock)
                .complete(REQUEST, PARSER);

        assertThat(answer).hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(B));
    }

    @Test
    void unknownModel_skipped() {
        stub.replyFor(A, Reply.status(404, "{\"error\":{\"message\":\"The model `model-a` does not exist or you do not "
                + "have access to it.\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"))
                .replyFor(B, Reply.content(GOOD));
        LlmChain chain = chain(A, B);

        assertThat(chain.complete(REQUEST, PARSER)).hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(B));
        assertThat(chain.complete(REQUEST, PARSER)).isPresent();

        assertThat(stub.hits(A)).as("an unknown model is not asked again for a while").isEqualTo(1);
        assertThat(stub.hits(B)).isEqualTo(2);
    }

    @Test
    void decommissionedModel_skipped() {
        stub.replyFor(A, Reply.status(400, "{\"error\":{\"message\":\"The model has been decommissioned\","
                + "\"type\":\"invalid_request_error\",\"code\":\"model_decommissioned\"}}"))
                .replyFor(B, Reply.content(GOOD));
        LlmChain chain = chain(A, B);

        chain.complete(REQUEST, PARSER);
        chain.complete(REQUEST, PARSER);

        assertThat(stub.hits(A)).isEqualTo(1);
    }

    @Test
    void allFail_empty() {
        stub.replyFor(A, Reply.status(500, "{\"error\":{\"message\":\"internal\"}}"))
                .replyFor(B, Reply.status(200, "<html>not json</html>"))
                .replyFor(C, Reply.content("{\"ok\":\"wrong type\"}"));

        Optional<LlmAnswer<String>> answer;
        try (LogCapture logs = LogCapture.of(LlmChain.class)) {
            answer = chain(A, B, C).complete(REQUEST, PARSER);
            assertThat(logs.events()).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("ai unavailable"));
        }

        assertThat(answer).isEmpty();
        assertThat(stub.hits(A)).isEqualTo(1);
        assertThat(stub.hits(B)).isEqualTo(1);
        assertThat(stub.hits(C)).isEqualTo(1);
    }

    @Test
    void serverErrors_next_noRetryStorm() {
        stub.replyFor(A, Reply.status(503, "")).replyFor(B, Reply.status(502, "")).replyFor(C, Reply.content(GOOD));

        assertThat(chain(A, B, C).complete(REQUEST, PARSER))
                .hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(C));

        assertThat(stub.requests()).hasSize(3);
    }

    @Test
    void truncatedAnswer_next() {
        String truncated = GroqStub.Reply.content(GOOD).body().replace("\"stop\"", "\"length\"");
        stub.replyFor(A, Reply.status(200, truncated)).replyFor(B, Reply.content(GOOD));

        assertThat(chain(A, B).complete(REQUEST, PARSER))
                .hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(B));
    }

    @Test
    void invalidOutput_next() {
        stub.replyFor(A, Reply.content("{\"unexpected\":true}")).replyFor(B, Reply.content(GOOD));

        assertThat(chain(A, B).complete(REQUEST, PARSER))
                .hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(B));
    }

    @Test
    void authFailed_stopsChain_warns() {
        stub.replyFor(A, Reply.status(401, "{\"error\":{\"message\":\"Invalid API Key\",\"type\":"
                + "\"invalid_request_error\",\"code\":\"invalid_api_key\"}}"));

        Optional<LlmAnswer<String>> answer;
        try (LogCapture logs = LogCapture.of(LlmChain.class)) {
            answer = chain(A, B, C).complete(REQUEST, PARSER);
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel().toString()).isEqualTo("WARN");
                assertThat(event.getFormattedMessage()).contains("key rejected");
            });
        }

        assertThat(answer).isEmpty();
        assertThat(stub.hits(B)).as("every model shares the key").isZero();
        assertThat(stub.hits(C)).isZero();
    }

    @Test
    void disabled_noKey_noCall() {
        LlmChain chain = new LlmChain(new AiProperties(List.of(" "), "groq:" + A, URI.create(stub.baseUrl()),
                Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofHours(1), 6000, 1200, 800),
                client(URI.create(stub.baseUrl()), Duration.ofSeconds(2)), clock);

        assertThat(chain.enabled()).isFalse();
        assertThat(chain.complete(REQUEST, PARSER)).isEmpty();
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void disabled_warnedOnceAtStartup() {
        // P13 (silent failure 14): a production install without a key is a misconfiguration, not routine.
        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(LlmChain.class)) {
            new LlmChain(new AiProperties(List.of(" "), "groq:" + A, URI.create(stub.baseUrl()),
                    Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofHours(1), 6000, 1200, 800),
                    client(URI.create(stub.baseUrl()), Duration.ofSeconds(2)), clock);
            events = logs.events();
        }

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("ai not configured");
        });
    }

    @Test
    void parserThrows_warnedWithItsClass_nextModel() {
        // P13 (silent failure 10): a parser bug must not look like invalid model output in the logs.
        stub.replyFor(A, Reply.content(GOOD)).replyFor(B, Reply.content(GOOD));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Function<String, Optional<String>> fragile = content -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("SECRET-ANSWER-DETAIL");
            }
            return PARSER.apply(content);
        };

        Optional<LlmAnswer<String>> answer;
        List<ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(LlmChain.class)) {
            answer = chain(A, B).complete(REQUEST, fragile);
            events = logs.events();
        }

        assertThat(answer).hasValueSatisfying(value -> assertThat(value.model()).isEqualTo(B));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("IllegalStateException", "task=extract", A);
        });
        assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("SECRET-ANSWER-DETAIL"));
    }

    @Test
    void requestSettings_v09() {
        String gptOss = "openai/gpt-oss-120b";
        String qwen = "qwen/qwen3.8-27b";
        stub.replyFor(gptOss, Reply.status(503, "")).replyFor(qwen, Reply.content(GOOD));

        chain(gptOss, qwen).complete(REQUEST, PARSER);

        JsonNode first = stub.requests().get(0).json();
        assertThat(stub.requests().get(0).authorization()).isEqualTo("Bearer " + KEY);
        assertThat(first.path("model").stringValue()).isEqualTo(gptOss);
        assertThat(first.path("messages").get(0).path("role").stringValue()).isEqualTo("system");
        assertThat(first.path("messages").get(0).path("content").stringValue()).isEqualTo(REQUEST.systemPrompt());
        assertThat(first.path("messages").get(1).path("role").stringValue()).isEqualTo("user");
        assertThat(first.path("messages").get(1).path("content").stringValue()).isEqualTo(REQUEST.userMessage());
        assertThat(first.path("messages")).hasSize(2);
        assertThat(first.path("temperature").asInt()).isZero();
        assertThat(first.path("max_completion_tokens").asInt()).isEqualTo(900);
        assertThat(first.path("response_format").path("type").stringValue()).isEqualTo("json_object");
        assertThat(first.path("include_reasoning").asBoolean(true)).isFalse();
        assertThat(first.path("reasoning_effort").stringValue()).isEqualTo("low");
        assertThat(first.path("stream").asBoolean(true)).isFalse();
        JsonNode second = stub.requests().get(1).json();
        assertThat(second.path("reasoning_effort").stringValue()).isEqualTo("none");
    }

    @Test
    void logs_tokensButNeverPromptsAnswersOrKey() {
        stub.replyFor(A, Reply.status(503, "{\"error\":{\"message\":\"ANSWER-MARKER\"}}"))
                .replyFor(B, Reply.content(GOOD));

        try (LogCapture logs = LogCapture.root()) {
            chain(A, B).complete(REQUEST, PARSER);
            List<String> lines = logs.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .contains("model=" + B, "outcome=ok", "promptTokens=900", "completionTokens=150",
                            "reasoningTokens=60"));
            assertThat(String.join("\n", lines)).doesNotContain("SYSTEM-PROMPT-MARKER", "USER-LETTER-MARKER",
                    "ANSWER-MARKER", KEY, "\"ok\":true");
        }
    }

    @Test
    void parseDuration_goStyle() {
        assertThat(RateLimits.parseDuration("2m59.56s")).isEqualTo(Duration.ofMillis(179_560));
        assertThat(RateLimits.parseDuration("7.66s")).isEqualTo(Duration.ofMillis(7_660));
        assertThat(RateLimits.parseDuration("1h2m3s")).isEqualTo(Duration.ofSeconds(3_723));
        assertThat(RateLimits.parseDuration("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(RateLimits.parseDuration("14m24s")).isEqualTo(Duration.ofSeconds(864));
        assertThat(RateLimits.parseDuration("12")).isEqualTo(Duration.ofSeconds(12));
        assertThat(RateLimits.parseDuration("soon")).isNull();
        assertThat(RateLimits.parseDuration("")).isNull();
        assertThat(RateLimits.parseDuration(null)).isNull();
        assertThat(RateLimits.parseDuration("-5s")).isNull();
    }

    private LlmChain chain(String... models) {
        return chain(Duration.ofSeconds(2), models);
    }

    private LlmChain chain(Duration timeout, String... models) {
        return new LlmChain(properties(timeout, models), client(URI.create(stub.baseUrl()), timeout), clock);
    }

    private static AiProperties properties(Duration timeout, String... models) {
        String chain = String.join(",", java.util.Arrays.stream(models).map(model -> "groq:" + model).toList());
        return new AiProperties(List.of(KEY), chain, URI.create("http://unused.invalid"), timeout,
                Duration.ofSeconds(60), Duration.ofHours(1), 6000, 1200, 800);
    }

    private GroqClient client(URI baseUrl, Duration timeout) {
        return new GroqClient(new AiProperties(List.of(KEY), "groq:" + A, baseUrl, timeout, Duration.ofSeconds(60),
                Duration.ofHours(1), 6000, 1200, 800), JsonMapper.builder().build());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
