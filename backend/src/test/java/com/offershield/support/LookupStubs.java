package com.offershield.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.xbill.DNS.Resolver;

/**
 * The online lookups of C5/C6 in integration tests: a local RDAP stub (the bootstrap file lists {@code .com} and
 * {@code .in}, served over plain HTTP) and an in-memory DNS resolver, both shared per JVM. Nothing leaves the machine.
 */
public final class LookupStubs {

    public static final RdapStub RDAP = RdapStub.start().bootstrapFor("com", "in");
    public static final FakeDnsResolver DNS = new FakeDnsResolver();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RDAP::close, "rdap-stub-stop"));
    }

    private LookupStubs() {
    }

    /** Replaces the application's DNS resolver with {@link #DNS}. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class TestLookups {

        @Bean
        @Primary
        Resolver testDnsResolver() {
            return DNS;
        }
    }
}
