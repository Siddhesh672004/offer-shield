package com.offershield.company;

import com.offershield.auth.AppOidcUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin-only global known-company list (PRD §4 F8, §11; D-24): every other role gets 403. The acting admin always
 * comes from the session principal; every change is audit-logged (R-E).
 */
@RestController
@RequestMapping("/api/known-companies")
@PreAuthorize("hasRole('ADMIN')")
public class KnownCompanyController {

    private final KnownCompanyService service;

    public KnownCompanyController(KnownCompanyService service) {
        this.service = service;
    }

    @GetMapping
    public List<KnownCompanyResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnownCompanyResponse create(@Valid @RequestBody CreateKnownCompanyRequest request,
                                       @AuthenticationPrincipal AppOidcUser admin) {
        return service.create(request, admin.userId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal AppOidcUser admin) {
        service.delete(id, admin.userId());
    }

    @PostMapping("/{id}/domains")
    public KnownCompanyResponse addDomain(@PathVariable UUID id, @Valid @RequestBody AddKnownCompanyDomainRequest request,
                                          @AuthenticationPrincipal AppOidcUser admin) {
        return service.addDomain(id, request, admin.userId());
    }

    @DeleteMapping("/{id}/domains/{domain}")
    public KnownCompanyResponse removeDomain(@PathVariable UUID id, @PathVariable String domain,
                                             @AuthenticationPrincipal AppOidcUser admin) {
        return service.removeDomain(id, domain, admin.userId());
    }
}
