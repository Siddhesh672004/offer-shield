package com.offershield.common;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The fixed public list of free and disposable email providers (D-08), read once from
 * {@code common/free-mail-domains.txt}. Shared by the analysis checks (C2, C3, C4, C5, C6, C8), the known-company
 * list (a free-mail domain is never accepted as a company's official domain, P8) and, from P9, the registry's
 * shared-domain guard (a free-mail domain never becomes a scam marker).
 */
@Component
public class FreeMailDomains {

    static final String RESOURCE = "/common/free-mail-domains.txt";

    private final Set<String> domains;

    public FreeMailDomains() {
        this.domains = Set.copyOf(load());
    }

    /** True when {@code domain} (any case, optional trailing dot) is a free-mail provider's domain. */
    public boolean contains(String domain) {
        if (domain == null) {
            return false;
        }
        String normalised = domain.strip().toLowerCase(Locale.ROOT);
        if (normalised.endsWith(".")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        return domains.contains(normalised);
    }

    /** How many domains the list holds. */
    public int size() {
        return domains.size();
    }

    private static Set<String> load() {
        try (InputStream in = FreeMailDomains.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            Set<String> loaded = new HashSet<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String entry = line.strip().toLowerCase(Locale.ROOT);
                if (!entry.isEmpty() && !entry.startsWith("#")) {
                    loaded.add(entry);
                }
            }
            return loaded;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
