package com.offershield.analysis.lookup;

import com.offershield.support.LogCapture;
import com.offershield.support.RdapStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-04 / PRD §14.5: RDAP servers come only from the IANA bootstrap file (here a local stub), a redirect may only go to
 * another bootstrap server, and every failure is UNAVAILABLE within the timeout (D-31). No real network is used.
 */
class RdapClientTest {

    private static final Instant REGISTERED = Instant.parse("2026-09-01T10:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMillis(400);

    private final DomainValidator validator = new DomainValidator();
    private RdapStub stub;
    private RdapHttp http;

    @BeforeEach
    void startStub() {
        stub = RdapStub.start().bootstrapFor("com", "in");
    }

    @AfterEach
    void stopStub() {
        stub.close();
        if (http != null) {
            http.close();
        }
    }

    @Test
    void registrationEvent_found() {
        stub.registered("young-firm.com", REGISTERED);

        RdapLookup result = client(false).lookup(domain("hr.young-firm.com"));

        assertThat(result.status()).isEqualTo(RdapLookup.Status.FOUND);
        assertThat(result.registeredAt()).isEqualTo(REGISTERED);
        // Asked for the registered name, never the full host.
        assertThat(stub.requests()).containsExactly("/bootstrap.json", "/rdap/domain/young-firm.com");
    }

    @Test
    void tldNotInBootstrap_unavailable() {
        RdapLookup result = client(false).lookup(domain("acme-careers.org"));

        assertThat(result.status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
        assertThat(stub.requests()).containsExactly("/bootstrap.json");
    }

    @Test
    void redirectOffList_refused() {
        stub.reply("/rdap/domain/moved.com", new RdapStub.Reply(302,
                Map.of("Location", "http://169.254.169.254/latest/meta-data/"), "", Duration.ZERO));

        RdapLookup result = client(false).lookup(domain("moved.com"));

        assertThat(result.status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
        assertThat(stub.requests()).containsExactly("/bootstrap.json", "/rdap/domain/moved.com");
    }

    @Test
    void redirectToOffListOrigin_neverRequested() {
        // P13 (test gap 2): a real second server that is not in the bootstrap file, so a missing allow-list check would
        // be observable as a request to it.
        try (RdapStub offList = RdapStub.start()) {
            stub.reply("/rdap/domain/moved-away.com", new RdapStub.Reply(302,
                    Map.of("Location", offList.baseUrl() + "/rdap/domain/moved-away.com"), "", Duration.ZERO));
            offList.registered("moved-away.com", REGISTERED);

            long start = System.nanoTime();
            RdapLookup result;
            try (LogCapture logs = LogCapture.of(RdapClient.class)) {
                result = client(false).lookup(domain("moved-away.com"));
                assertThat(logs.events()).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("rdap redirect refused");
                });
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(result.status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
            assertThat(offList.requests()).isEmpty();
            assertThat(took).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void tooManyRedirects_unavailable() {
        // P13 (test gap 2): at most MAX_REDIRECTS (2) hops, even inside the allow-list.
        stub.reply("/rdap/domain/hop0.com", new RdapStub.Reply(301, Map.of("Location", "/rdap/domain/hop1.com"), "",
                Duration.ZERO));
        stub.reply("/rdap/domain/hop1.com", new RdapStub.Reply(301, Map.of("Location", "/rdap/domain/hop2.com"), "",
                Duration.ZERO));
        stub.reply("/rdap/domain/hop2.com", new RdapStub.Reply(301, Map.of("Location", "/rdap/domain/hop3.com"), "",
                Duration.ZERO));
        stub.registered("hop3.com", REGISTERED);

        RdapLookup result = client(false).lookup(domain("hop0.com"));

        assertThat(result.status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
        assertThat(stub.requests()).endsWith("/rdap/domain/hop0.com", "/rdap/domain/hop1.com", "/rdap/domain/hop2.com")
                .doesNotContain("/rdap/domain/hop3.com");
    }

    @Test
    void redirectToBootstrapServer_followed() {
        stub.reply("/rdap/domain/onlist.com", new RdapStub.Reply(301,
                Map.of("Location", "/rdap/domain/onlist-registrar.com"), "", Duration.ZERO));
        stub.registered("onlist-registrar.com", REGISTERED);

        RdapLookup result = client(false).lookup(domain("onlist.com"));

        assertThat(result.status()).isEqualTo(RdapLookup.Status.FOUND);
        assertThat(stub.requests()).endsWith("/rdap/domain/onlist.com", "/rdap/domain/onlist-registrar.com");
    }

    @Test
    void timeout_unavailable() {
        stub.reply("/rdap/domain/slow.com", new RdapStub.Reply(200, Map.of(), "{\"events\":[]}", Duration.ofSeconds(3)));
        RdapClient client = client(false);
        client.lookup(domain("warm-up.com")); // loads the bootstrap file first

        long start = System.nanoTime();
        RdapLookup result = client.lookup(domain("slow.com"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
        assertThat(took).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void cached_noSecondCall() {
        stub.registered("cached.com", REGISTERED);
        RdapClient client = client(false);

        RdapLookup first = client.lookup(domain("cached.com"));
        RdapLookup second = client.lookup(domain("www.cached.com"));

        assertThat(second).isEqualTo(first);
        assertThat(stub.hits("/rdap/domain/cached.com")).isEqualTo(1);
        assertThat(stub.hits("/bootstrap.json")).isEqualTo(1);
    }

    @Test
    void failure_notCached() {
        RdapClient client = client(false);

        client.lookup(domain("missing.com"));
        client.lookup(domain("missing.com"));

        assertThat(stub.hits("/rdap/domain/missing.com")).isEqualTo(2);
    }

    @Test
    void missingRegistrationEvent_unavailable() {
        stub.reply("/rdap/domain/nixi-style.in", new RdapStub.Reply(200, Map.of(),
                "{\"objectClassName\":\"domain\",\"events\":[{\"eventAction\":\"last changed\","
                        + "\"eventDate\":\"2026-01-01T00:00:00Z\"}]}", Duration.ZERO));

        RdapLookup result = client(false).lookup(domain("nixi-style.in"));

        assertThat(result.status()).isEqualTo(RdapLookup.Status.NO_REGISTRATION_DATE);
        assertThat(result.registeredAt()).isNull();
    }

    @Test
    void bodyTooLarge_unavailable() {
        stub.reply("/rdap/domain/huge.com", new RdapStub.Reply(200, Map.of(),
                "{\"padding\":\"" + "x".repeat(300_000) + "\"}", Duration.ZERO));

        assertThat(client(false).lookup(domain("huge.com")).status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
    }

    @Test
    void invalidJson_unavailable() {
        stub.reply("/rdap/domain/broken.com", new RdapStub.Reply(200, Map.of(), "<html>not rdap", Duration.ZERO));

        assertThat(client(false).lookup(domain("broken.com")).status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
    }

    @Test
    void httpsRequired_plainHttpServersNeverCalled() {
        stub.registered("young-firm.com", REGISTERED);

        RdapLookup result = client(true).lookup(domain("young-firm.com"));

        assertThat(result.status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
        assertThat(stub.requests()).containsExactly("/bootstrap.json");
    }

    @Test
    void bootstrapDown_unavailable() {
        stub.bootstrapStatus(503);

        assertThat(client(false).lookup(domain("young-firm.com")).status()).isEqualTo(RdapLookup.Status.UNAVAILABLE);
        assertThat(stub.requests()).containsExactly("/bootstrap.json");
    }

    @Test
    void bootstrapPrefersHttpsAndIgnoresBadEntries() {
        stub.bootstrapJson("{\"services\":[[[\"com\"],[\"ftp://x.example/\",\"" + stub.baseUrl() + "/rdap\"]],"
                + "[[\"net\"],[]],\"garbage\"]}");
        stub.registered("young-firm.com", REGISTERED);

        assertThat(client(false).lookup(domain("young-firm.com")).status()).isEqualTo(RdapLookup.Status.FOUND);
        assertThat(stub.requests()).containsExactly("/bootstrap.json", "/rdap/domain/young-firm.com");
    }

    private RdapClient client(boolean requireHttps) {
        LookupProperties properties = new LookupProperties(
                new LookupProperties.Rdap(stub.bootstrapUrl(), TIMEOUT, requireHttps, 256 * 1024, Duration.ofHours(24)),
                new LookupProperties.Dns(Duration.ofSeconds(3), Duration.ofHours(1), List.of()));
        JsonMapper json = JsonMapper.builder().build();
        http = new RdapHttp(properties);
        return new RdapClient(new RdapBootstrap(properties, http, json), http, json, properties);
    }

    private ValidatedDomain domain(String name) {
        return validator.validate(name).orElseThrow();
    }
}
