package com.offershield.ai;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Groq's OpenAI-compatible chat completions (PRD §8.2, ADR-006, D-11): one {@link RestClient} on the JDK HTTP client,
 * {@code POST <baseUrl>/chat/completions} with the first key of {@code GROQ_API_KEYS}. Each call has the configured
 * timeout (10 s) for the answer, at most 3 s to connect, never follows redirects, and reads at most 256 KB.
 *
 * <p>Request settings (V-09): {@code temperature 0}, {@code response_format: json_object}, the task's
 * {@code max_completion_tokens} (≤ 1,500), no streaming; for reasoning models {@code include_reasoning: false} and
 * {@code reasoning_effort} "low" (gpt-oss) or "none" (qwen).
 *
 * <p>Never throws and never logs: every result, including timeouts and refused connections, becomes a
 * {@link ProviderReply} for {@link LlmChain}. Prompts, answers and the key never appear in a reply's reason.
 */
@Component
public class GroqClient implements ChatProvider {

    static final int MAX_ANSWER_BYTES = 256 * 1024;
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Set<String> UNKNOWN_MODEL_CODES = Set.of("model_not_found", "model_decommissioned");

    private final RestClient rest;
    private final JsonMapper json;
    private final String apiKey;
    private final Duration defaultRateLimitWait;

    public GroqClient(AiProperties properties, JsonMapper json) {
        Duration connectTimeout = properties.timeout().compareTo(MAX_CONNECT_TIMEOUT) < 0
                ? properties.timeout() : MAX_CONNECT_TIMEOUT;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        this.rest = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .build();
        this.json = json;
        this.apiKey = properties.apiKey();
        this.defaultRateLimitWait = properties.defaultRateLimitWait();
    }

    @Override
    public String name() {
        return "groq";
    }

    @Override
    public ProviderReply complete(String model, ChatRequest request) {
        if (apiKey == null) {
            return ProviderReply.failed(ProviderReply.Outcome.FAILED, 0, "not-configured");
        }
        try {
            byte[] body = json.writeValueAsBytes(requestBody(model, request));
            return rest.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((clientRequest, response) -> read(response), true);
        } catch (RuntimeException ex) {
            return ProviderReply.failed(ProviderReply.Outcome.FAILED, 0, reasonOf(ex));
        }
    }

    ObjectNode requestBody(String model, ChatRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userMessage());
        body.put("temperature", 0);
        body.put("max_completion_tokens", request.maxCompletionTokens());
        body.putObject("response_format").put("type", "json_object");
        body.put("stream", false);
        String effort = reasoningEffort(model);
        if (effort != null) {
            body.put("reasoning_effort", effort);
            body.put("include_reasoning", false);
        }
        return body;
    }

    /** "low" for gpt-oss, "none" for qwen (04 P7-01); other models get no reasoning settings. */
    static String reasoningEffort(String model) {
        String name = model.toLowerCase(Locale.ROOT);
        if (name.startsWith("openai/gpt-oss")) {
            return "low";
        }
        return name.startsWith("qwen/") ? "none" : null;
    }

    private ProviderReply read(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        byte[] bytes = readLimited(response.getBody());
        if (bytes.length > MAX_ANSWER_BYTES) {
            return ProviderReply.failed(ProviderReply.Outcome.FAILED, status, "answer-too-large");
        }
        if (status == 429) {
            return ProviderReply.rateLimited(RateLimits.restFor(response.getHeaders(), defaultRateLimitWait));
        }
        if (status == 401 || status == 403) {
            return ProviderReply.failed(ProviderReply.Outcome.AUTH_FAILED, status, "key-rejected");
        }
        String errorCode = status == 200 ? null : errorCode(bytes);
        if (errorCode != null && UNKNOWN_MODEL_CODES.contains(errorCode)) {
            return ProviderReply.failed(ProviderReply.Outcome.UNKNOWN_MODEL, status, "unknown-model");
        }
        if (status != 200) {
            return ProviderReply.failed(ProviderReply.Outcome.FAILED, status, "status-" + status);
        }
        return answer(bytes);
    }

    private ProviderReply answer(byte[] bytes) {
        JsonNode root;
        try {
            root = bytes.length == 0 ? null : json.readTree(bytes);
        } catch (JacksonException ex) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            return ProviderReply.failed(ProviderReply.Outcome.FAILED, 200, "invalid-json");
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode finish = choice.path("finish_reason");
        if (finish.isString() && "length".equals(finish.stringValue())) {
            return ProviderReply.failed(ProviderReply.Outcome.FAILED, 200, "truncated");
        }
        JsonNode content = choice.path("message").path("content");
        if (!content.isString() || content.stringValue().isBlank()) {
            return ProviderReply.failed(ProviderReply.Outcome.FAILED, 200, "no-content");
        }
        JsonNode usage = root.path("usage");
        return ProviderReply.ok(content.stringValue(), new ProviderReply.Usage(count(usage.path("prompt_tokens")),
                count(usage.path("completion_tokens")),
                count(usage.path("completion_tokens_details").path("reasoning_tokens"))));
    }

    private String errorCode(byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            JsonNode root = json.readTree(bytes);
            JsonNode code = root == null ? null : root.path("error").path("code");
            return code != null && code.isString() ? code.stringValue() : null;
        } catch (JacksonException ex) {
            return null;
        }
    }

    private static int count(JsonNode node) {
        return node.isIntegralNumber() ? node.intValue() : -1;
    }

    private static byte[] readLimited(InputStream body) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        try (body) {
            return body.readNBytes(MAX_ANSWER_BYTES + 1);
        }
    }

    /** A content-free reason: "timeout", "connection-refused" or the exception class. */
    private static String reasonOf(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return "timeout";
            }
            if (cause instanceof ConnectException) {
                return "connection-refused";
            }
        }
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
