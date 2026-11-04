package com.offershield.analysis.lookup;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one HTTP client of the RDAP lookups (JDK {@link HttpClient}): never follows redirects by itself (the caller
 * decides, PRD §14.5), uses no proxy, gives every call one deadline that covers connecting, headers and body, and
 * stops reading a body at {@code maxBytes}.
 */
@Component
class RdapHttp implements AutoCloseable {

    record Response(int status, String location, byte[] body) {
    }

    private final HttpClient client;

    RdapHttp(LookupProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.rdap().timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    Response get(URI uri, Duration timeout, int maxBytes) throws LookupFailedException {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new LookupFailedException("timeout");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/rdap+json, application/json")
                .header("User-Agent", "OfferShield")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new LimitedBody(maxBytes));
        try {
            HttpResponse<byte[]> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.headers().firstValue("Location").orElse(null),
                    response.body());
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new LookupFailedException("timeout");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new LookupFailedException(cause.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new LookupFailedException("interrupted");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        client.shutdownNow();
    }

    /** Collects the body and fails (cancelling the download) as soon as it grows past the limit. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBody(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (body.size() + item.remaining() > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("body too large"));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                body.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(body.toByteArray());
        }
    }
}
