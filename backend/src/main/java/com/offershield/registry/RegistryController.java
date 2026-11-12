package com.offershield.registry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/registry/domains/{domain}} for any signed-in user (PRD §11; 401 when signed out). */
@RestController
@RequestMapping("/api/registry")
public class RegistryController {

    private final DomainStatusService domainStatus;

    public RegistryController(DomainStatusService domainStatus) {
        this.domainStatus = domainStatus;
    }

    @GetMapping("/domains/{domain}")
    public DomainStatusResponse domain(@PathVariable String domain) {
        return domainStatus.lookup(domain);
    }
}
