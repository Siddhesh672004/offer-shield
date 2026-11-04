package com.offershield.analysis.lookup;

import com.google.common.net.InternetDomainName;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The SSRF guard (PRD §14.5, never cut). A sender domain may reach RDAP or DNS only as a {@link ValidatedDomain}:
 * <ol>
 *   <li>at most 253 characters, made only of letters (any script), digits, dots and hyphens, so IPv6 literals,
 *       brackets, ports, paths, schemes, user info, '%' escapes and whitespace are rejected outright;</li>
 *   <li>converted with {@code IDN.toASCII} (STD3 rules) and lower-cased;</li>
 *   <li>at least two labels of 1–63 letters, digits or inner hyphens, so {@code localhost} and decimal IPs such as
 *       {@code 2130706433} fail;</li>
 *   <li>an alphabetic or punycode top-level label, so every IPv4 spelling ({@code 127.0.0.1}, {@code 0x7f.0.0.1},
 *       {@code 127.1}) fails;</li>
 *   <li>not under a local or special-use name ({@code .localhost}, {@code .local}, {@code .internal}, {@code .arpa},
 *       {@code .test}, {@code .example}, …);</li>
 *   <li>under a public suffix from the public suffix list (M-06, Guava), and not a public suffix itself.</li>
 * </ol>
 * Only the domain name is ever looked up: no URL found in a letter is fetched, and the RDAP host always comes from the
 * IANA bootstrap file.
 */
@Component
public class DomainValidator {

    static final int MAX_LENGTH = 253;
    private static final Pattern RAW = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}.-]+");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern TOP_LEVEL = Pattern.compile("[a-z]{2,63}|xn--[a-z0-9-]{1,59}");
    private static final Set<String> SPECIAL_USE = Set.of("localhost", "local", "internal", "localdomain", "lan",
            "home", "corp", "intranet", "arpa", "invalid", "test", "example", "onion", "alt");

    public Optional<ValidatedDomain> validate(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String candidate = raw.strip();
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isEmpty() || candidate.length() > MAX_LENGTH || !RAW.matcher(candidate).matches()) {
            return Optional.empty();
        }
        String ascii;
        try {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (ascii.isEmpty() || ascii.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < 2) {
            return Optional.empty();
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return Optional.empty();
            }
        }
        String topLevel = labels[labels.length - 1];
        if (!TOP_LEVEL.matcher(topLevel).matches() || SPECIAL_USE.contains(topLevel)) {
            return Optional.empty();
        }
        return publicName(ascii);
    }

    private static Optional<ValidatedDomain> publicName(String ascii) {
        InternetDomainName name;
        try {
            name = InternetDomainName.from(ascii);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!name.isUnderPublicSuffix()) {
            return Optional.empty(); // a public suffix itself ("co.in") or an unknown top-level name
        }
        String registrable = name.topPrivateDomain().toString();
        String registry = name.isUnderRegistrySuffix() ? name.topDomainUnderRegistrySuffix().toString() : registrable;
        return Optional.of(new ValidatedDomain(ascii, registrable, registry));
    }
}
