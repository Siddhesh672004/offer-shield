package com.offershield.analysis.lookup;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Resolver;

import java.net.UnknownHostException;
import java.util.List;

/** The DNS resolver for C6: the configured servers, else the system's resolvers (in Docker, the embedded DNS). */
@Configuration(proxyBeanMethods = false)
class LookupConfig {

    @Bean
    @Lazy
    Resolver dnsResolver(LookupProperties properties) throws UnknownHostException {
        List<String> servers = properties.dns().servers();
        ExtendedResolver resolver = servers.isEmpty()
                ? new ExtendedResolver()
                : new ExtendedResolver(servers.toArray(String[]::new));
        resolver.setTimeout(properties.dns().timeout());
        return resolver;
    }
}
