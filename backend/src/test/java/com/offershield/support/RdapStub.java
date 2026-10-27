package com.offershield.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A local stand-in for the IANA RDAP bootstrap file and an RDAP server (JDK {@link HttpServer} on 127.0.0.1), so no
 * test reaches the internet. Serves {@code /bootstrap.json} and {@code /rdap/domain/<name>}; records every path asked.
 */
public final class RdapStub implements AutoCloseable {

    /** One canned answer: status, extra headers, body, and an optional delay before answering. */
    public record Reply(int status, Map<String, String> headers, String body, Duration delay) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Reply bootstrap;

    private RdapStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static RdapStub start() {
        return new RdapStub();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public URI bootstrapUrl() {
        return URI.create(baseUrl() + "/bootstrap.json");
    }

    /** A bootstrap file mapping {@code tlds} to this stub's {@code /rdap/} (plain HTTP). */
    public RdapStub bootstrapFor(String... tlds) {
        return bootstrapJson("{\"version\":\"1.0\",\"services\":[[[" + quoted(tlds) + "],[\"" + baseUrl() + "/rdap/\"]]]}");
    }

    public RdapStub bootstrapJson(String json) {
        bootstrap = new Reply(200, Map.of("Content-Type", "application/json"), json, Duration.ZERO);
        return this;
    }

    public RdapStub bootstrapStatus(int status) {
        bootstrap = new Reply(status, Map.of(), "", Duration.ZERO);
        return this;
    }

    /** {@code /rdap/domain/<domain>} answers with a registration event on {@code registeredAt}. */
    public RdapStub registered(String domain, Instant registeredAt) {
        return reply("/rdap/domain/" + domain, new Reply(200, Map.of("Content-Type", "application/rdap+json"),
                "{\"objectClassName\":\"domain\",\"ldhName\":\"" + domain + "\",\"events\":["
                        + "{\"eventAction\":\"last changed\",\"eventDate\":\"2026-01-01T00:00:00Z\"},"
                        + "{\"eventAction\":\"registration\",\"eventDate\":\"" + registeredAt + "\"}]}",
                Duration.ZERO));
    }

    public RdapStub reply(String path, Reply reply) {
        replies.put(path, reply);
        return this;
    }

    /** Every path requested so far, in order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public long hits(String path) {
        return requests.stream().filter(path::equals).count();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(path);
        Reply reply = "/bootstrap.json".equals(path) ? bootstrap : replies.get(path);
        if (reply == null) {
            reply = new Reply(404, Map.of(), "{\"errorCode\":404}", Duration.ZERO);
        }
        try {
            if (!reply.delay().isZero()) {
                Thread.sleep(reply.delay().toMillis());
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        } finally {
            exchange.close();
        }
    }

    private static String quoted(String... values) {
        return Arrays.stream(values).map(value -> "\"" + value + "\"").collect(Collectors.joining(","));
    }
}
