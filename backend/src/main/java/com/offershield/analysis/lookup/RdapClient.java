package com.offershield.analysis.lookup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Domain registration date over RDAP (C5, D-19). Accepts only a {@link ValidatedDomain}, asks the server the IANA
 * bootstrap file names for its top-level domain ({@code GET <server>domain/<name>}), and follows at most
 * {@value #MAX_REDIRECTS} redirects, each only to another bootstrap server (PRD §14.5). One 5 s deadline covers the
 * whole lookup; answers are capped at 256 KB. A found date (or an answer without one) is cached for 24 h; failures are
 * not cached. Every failure returns {@code UNAVAILABLE} (D-31) and is logged without the domain name (D-27).
 */
@Component
public class RdapClient {

    static final int MAX_REDIRECTS = 2;
    private static final Logger log = LoggerFactory.getLogger(RdapClient.class);

    private final RdapBootstrap bootstrap;
    private final RdapHttp http;
    private final JsonMapper json;
    private final Duration timeout;
    private final int maxBodyBytes;
    private final Cache<String, RdapLookup> cache;

    RdapClient(RdapBootstrap bootstrap, RdapHttp http, JsonMapper json, LookupProperties properties) {
        this.bootstrap = bootstrap;
        this.http = http;
        this.json = json;
        this.timeout = properties.rdap().timeout();
        this.maxBodyBytes = properties.rdap().maxBodyBytes();
        this.cache = Caffeine.newBuilder().expireAfterWrite(properties.rdap().cacheTtl()).maximumSize(10_000).build();
    }

    public RdapLookup lookup(ValidatedDomain domain) {
        String name = domain.registryDomain();
        RdapLookup cached = cache.getIfPresent(name);
        if (cached != null) {
            return cached;
        }
        RdapLookup result = fetch(name);
        if (result.status() != RdapLookup.Status.UNAVAILABLE) {
            cache.put(name, result);
        }
        return result;
    }

    private RdapLookup fetch(String name) {
        try {
            Optional<URI> server = bootstrap.serverFor(name);
            if (server.isEmpty()) {
                log.info("rdap lookup outcome=UNAVAILABLE reason=no-server-for-tld");
                return RdapLookup.unavailable();
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            URI uri = server.get().resolve("domain/" + name);
            for (int redirects = 0; ; redirects++) {
                RdapHttp.Response response = http.get(uri, Duration.ofNanos(deadline - System.nanoTime()), maxBodyBytes);
                if (response.status() == 200) {
                    RdapLookup result = parse(response.body());
                    log.info("rdap lookup outcome={} server={}", result.status(), uri.getHost());
                    return result;
                }
                if (isRedirect(response.status()) && response.location() != null && redirects < MAX_REDIRECTS) {
                    URI next = resolve(uri, response.location());
                    if (next == null || !bootstrap.allows(next)) {
                        log.warn("rdap redirect refused: target is not an IANA bootstrap server");
                        return RdapLookup.unavailable();
                    }
                    uri = next;
                    continue;
                }
                log.info("rdap lookup outcome=UNAVAILABLE status={} server={}", response.status(), uri.getHost());
                return RdapLookup.unavailable();
            }
        } catch (LookupFailedException ex) {
            log.info("rdap lookup outcome=UNAVAILABLE reason={}", ex.getMessage());
            return RdapLookup.unavailable();
        }
    }

    private RdapLookup parse(byte[] body) throws LookupFailedException {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException ex) {
            throw new LookupFailedException("answer not JSON");
        }
        for (JsonNode event : root.path("events")) {
            JsonNode action = event.path("eventAction");
            JsonNode date = event.path("eventDate");
            if (action.isString() && "registration".equalsIgnoreCase(action.stringValue()) && date.isString()) {
                Instant registeredAt = parseDate(date.stringValue());
                return registeredAt == null ? RdapLookup.noRegistrationDate() : RdapLookup.found(registeredAt);
            }
        }
        return RdapLookup.noRegistrationDate();
    }

    /** RFC 3339 date-time (RDAP), or a plain date as some registries send. */
    static Instant parseDate(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a plain date next
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI resolve(URI base, String location) {
        try {
            return base.resolve(location);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
