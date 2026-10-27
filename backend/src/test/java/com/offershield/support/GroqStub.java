package com.offershield.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A local stand-in for Groq's OpenAI-compatible {@code POST /openai/v1/chat/completions} (JDK {@link HttpServer} on
 * 127.0.0.1), so no test reaches the internet or needs a key. Records every request (raw body bytes as text, the
 * Authorization header and the model). Replies come from a per-model override, else from the responder, which by
 * default answers an extraction with an empty, valid result and an explanation with a short, valid summary.
 */
public final class GroqStub implements AutoCloseable {

    /** One canned answer: status, extra headers, body, and an optional delay before answering. */
    public record Reply(int status, Map<String, String> headers, String body, Duration delay) {

        /** A 200 chat completion whose assistant message is {@code content}, with token usage. */
        public static Reply content(String content) {
            ObjectNode root = JSON.createObjectNode();
            root.put("id", "chatcmpl-stub");
            root.put("object", "chat.completion");
            ObjectNode choice = root.putArray("choices").addObject();
            choice.put("index", 0);
            choice.putObject("message").put("role", "assistant").put("content", content);
            choice.put("finish_reason", "stop");
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", 900);
            usage.put("completion_tokens", 150);
            usage.put("total_tokens", 1050);
            usage.putObject("completion_tokens_details").put("reasoning_tokens", 60);
            return new Reply(200, Map.of("Content-Type", "application/json"), JSON.writeValueAsString(root),
                    Duration.ZERO);
        }

        public static Reply status(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), body, Duration.ZERO);
        }

        public static Reply rateLimited(Map<String, String> headers) {
            return new Reply(429, headers, "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"tokens\","
                    + "\"code\":\"rate_limit_exceeded\"}}", Duration.ZERO);
        }

        public Reply delayed(Duration by) {
            return new Reply(status, headers, body, by);
        }
    }

    /** One recorded request: the model asked for, the Authorization header and the exact body sent. */
    public record Request(String model, String authorization, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    public static final String EMPTY_EXTRACTION = "{\"companyName\":null,\"recruiterName\":null,\"ctc\":null,"
            + "\"joiningDate\":null,\"phoneNumbers\":[],\"upiIds\":[],\"paymentMentions\":[]}";
    public static final String STUB_SUMMARY = "{\"summary\":\"The checks found warning signs in this letter. "
            + "Read each signal and its evidence below. Confirm the offer through the company's official website "
            + "or careers page, and never pay money to get a job.\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Function<Request, Reply> DEFAULT = request -> request.body().contains("companyName")
            ? Reply.content(EMPTY_EXTRACTION) : Reply.content(STUB_SUMMARY);

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Reply> byModel = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Reply> responder = DEFAULT;

    private GroqStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static GroqStub start() {
        return new GroqStub();
    }

    /** The base URL to configure as {@code offershield.ai.base-url}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1";
    }

    public GroqStub replyFor(String model, Reply reply) {
        byModel.put(model, reply);
        return this;
    }

    public GroqStub respond(Function<Request, Reply> responder) {
        this.responder = responder;
        return this;
    }

    /** Back to the default replies, forgetting recorded requests. */
    public GroqStub reset() {
        byModel.clear();
        requests.clear();
        responder = DEFAULT;
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public long hits(String model) {
        return requests.stream().filter(request -> model.equals(request.model())).count();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(modelOf(body), exchange.getRequestHeaders().getFirst("Authorization"), body);
            requests.add(request);
            Reply reply;
            if (!"/openai/v1/chat/completions".equals(exchange.getRequestURI().getPath())
                    || !"POST".equals(exchange.getRequestMethod())) {
                reply = Reply.status(404, "{\"error\":{\"message\":\"unknown path\"}}");
            } else {
                reply = byModel.getOrDefault(request.model(), null);
                if (reply == null) {
                    reply = responder.apply(request);
                }
            }
            if (!reply.delay().isZero()) {
                Thread.sleep(reply.delay().toMillis());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // the client gave up (timeout tests) or the body was not JSON
        } finally {
            exchange.close();
        }
    }

    private static String modelOf(String body) {
        try {
            JsonNode model = JSON.readTree(body).path("model");
            return model.isString() ? model.stringValue() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
