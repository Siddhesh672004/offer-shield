package com.offershield.analysis.lookup;

/**
 * A domain name that passed the SSRF guard (PRD §14.5). Only {@link DomainValidator} can create one (package-private
 * constructor), and the RDAP and DNS clients accept nothing else, so an unchecked string can never reach the network.
 *
 * <ul>
 *   <li>{@code host}: the full name, lower-case ASCII (IDN converted), e.g. {@code hr.inf0sys-careers.co.in}</li>
 *   <li>{@code registrableDomain}: M-06, Guava {@code topPrivateDomain()}, e.g. {@code inf0sys-careers.co.in}</li>
 *   <li>{@code registryDomain}: the name the registry knows (ICANN suffixes only), used for RDAP; it differs only under
 *       private suffixes such as {@code someone.github.io} → {@code github.io}</li>
 * </ul>
 */
public final class ValidatedDomain {

    private final String host;
    private final String registrableDomain;
    private final String registryDomain;

    ValidatedDomain(String host, String registrableDomain, String registryDomain) {
        this.host = host;
        this.registrableDomain = registrableDomain;
        this.registryDomain = registryDomain;
    }

    public String host() {
        return host;
    }

    public String registrableDomain() {
        return registrableDomain;
    }

    public String registryDomain() {
        return registryDomain;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValidatedDomain that && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return host.hashCode();
    }

    @Override
    public String toString() {
        return "ValidatedDomain[" + host + "]";
    }
}
