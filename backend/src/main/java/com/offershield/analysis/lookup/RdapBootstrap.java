package com.offershield.analysis.lookup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The IANA RDAP bootstrap file (PRD §7.1, §14.5): which RDAP server answers for each top-level domain. It is the only
 * source of RDAP hosts, fetched from the configured URL and cached for 24 h (PRD §13.7). Server URLs must be HTTPS
 * (unless {@code requireHttps} is off for local tests) without user info; anything else in the file is ignored.
 */
@Component
class RdapBootstrap {

    static final int MAX_BOOTSTRAP_BYTES = 1 << 20;
    private static final String KEY = "dns";

    /** RDAP base URL (ending in '/') per top-level domain, and every allowed origin (for redirects). */
    record Servers(Map<String, URI> byTopLevelDomain, Set<String> origins) {
    }

    private final LookupProperties.Rdap properties;
    private final RdapHttp http;
    private final JsonMapper json;
    private final Cache<String, Servers> cache;

    RdapBootstrap(LookupProperties properties, RdapHttp http, JsonMapper json) {
        this.properties = properties.rdap();
        URI url = this.properties.bootstrapUrl();
        if (url == null || !url.isAbsolute() || !("https".equals(url.getScheme()) || "http".equals(url.getScheme()))) {
            throw new IllegalStateException("offershield.lookup.rdap.bootstrap-url must be an http(s) URL");
        }
        this.http = http;
        this.json = json;
        this.cache = Caffeine.newBuilder().expireAfterWrite(this.properties.cacheTtl()).maximumSize(1).build();
    }

    /** The RDAP base URL for {@code domain} (lower-case ASCII), from its longest matching suffix in the file. */
    Optional<URI> serverFor(String domain) throws LookupFailedException {
        Map<String, URI> byTld = servers().byTopLevelDomain();
        String suffix = domain;
        while (true) {
            URI server = byTld.get(suffix);
            if (server != null) {
                return Optional.of(server);
            }
            int dot = suffix.indexOf('.');
            if (dot < 0) {
                return Optional.empty();
            }
            suffix = suffix.substring(dot + 1);
        }
    }

    /** True when {@code uri} points at one of the bootstrap file's servers (scheme, host and port). */
    boolean allows(URI uri) throws LookupFailedException {
        return acceptable(uri) && servers().origins().contains(origin(uri));
    }

    private Servers servers() throws LookupFailedException {
        Servers cached = cache.getIfPresent(KEY);
        if (cached != null) {
            return cached;
        }
        Servers fetched = fetch();
        cache.put(KEY, fetched);
        return fetched;
    }

    private Servers fetch() throws LookupFailedException {
        RdapHttp.Response response = http.get(properties.bootstrapUrl(), properties.timeout(), MAX_BOOTSTRAP_BYTES);
        if (response.status() != 200) {
            throw new LookupFailedException("bootstrap status " + response.status());
        }
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (JacksonException ex) {
            throw new LookupFailedException("bootstrap not JSON");
        }
        Map<String, URI> byTld = new HashMap<>();
        Set<String> origins = new HashSet<>();
        for (JsonNode service : root.path("services")) {
            URI chosen = null;
            for (JsonNode url : service.path(1)) {
                URI candidate = url.isString() ? parse(url.stringValue()) : null;
                if (candidate == null || !acceptable(candidate)) {
                    continue;
                }
                origins.add(origin(candidate));
                if (chosen == null || "https".equals(candidate.getScheme()) && !"https".equals(chosen.getScheme())) {
                    chosen = candidate;
                }
            }
            if (chosen == null) {
                continue;
            }
            for (JsonNode tld : service.path(0)) {
                if (tld.isString()) {
                    byTld.put(tld.stringValue().toLowerCase(Locale.ROOT), chosen);
                }
            }
        }
        if (byTld.isEmpty()) {
            throw new LookupFailedException("bootstrap without servers");
        }
        return new Servers(Map.copyOf(byTld), Set.copyOf(origins));
    }

    private boolean acceptable(URI uri) {
        String scheme = uri.getScheme();
        boolean schemeOk = "https".equals(scheme) || "http".equals(scheme) && !properties.requireHttps();
        return uri.isAbsolute() && schemeOk && uri.getHost() != null && uri.getRawUserInfo() == null;
    }

    private static URI parse(String value) {
        try {
            URI uri = new URI(value.endsWith("/") ? value : value + "/");
            return uri.getRawQuery() == null && uri.getRawFragment() == null ? uri : null;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static String origin(URI uri) {
        int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
