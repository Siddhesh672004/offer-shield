package com.offershield.analysis.lookup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Online domain checks (D-19, D-31): RDAP for C5 and DNS for C6. Timeouts per CLAUDE.md (RDAP 5 s, DNS 3 s) and
 * Caffeine caches per PRD §13.7 (RDAP and the bootstrap file 24 h, DNS 1 h).
 *
 * @param rdap {@code bootstrapUrl} is the IANA file mapping each top-level domain to its RDAP servers; only servers
 *             listed there are ever called, over HTTPS unless {@code requireHttps} is false (local tests only);
 *             {@code maxBodyBytes} caps each RDAP answer
 * @param dns  {@code servers} are the resolvers to ask (empty = the system's resolvers)
 */
@ConfigurationProperties("offershield.lookup")
public record LookupProperties(@DefaultValue Rdap rdap, @DefaultValue Dns dns) {

    public record Rdap(@DefaultValue("https://data.iana.org/rdap/dns.json") URI bootstrapUrl,
                       @DefaultValue("5s") Duration timeout,
                       @DefaultValue("true") boolean requireHttps,
                       @DefaultValue("262144") int maxBodyBytes,
                       @DefaultValue("24h") Duration cacheTtl) {
    }

    public record Dns(@DefaultValue("3s") Duration timeout,
                      @DefaultValue("1h") Duration cacheTtl,
                      List<String> servers) {

        public Dns {
            servers = servers == null ? List.of() : List.copyOf(servers);
        }
    }
}
