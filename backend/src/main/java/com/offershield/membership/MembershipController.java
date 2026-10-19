package com.offershield.membership;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.web.IfMatch;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/memberships")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<MembershipResponse> request(@AuthenticationPrincipal AppOidcUser user,
                                                      @Valid @RequestBody CreateMembershipRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(membershipService.request(user.userId(), request));
    }

    @GetMapping("/me")
    public MembershipResponse mine(@AuthenticationPrincipal AppOidcUser user) {
        return membershipService.getMine(user.userId());
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('TPO')")
    public List<PendingMembershipResponse> pending(@AuthenticationPrincipal AppOidcUser user) {
        return membershipService.listPending(user.userId());
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('TPO')")
    public ResponseEntity<MembershipResponse> decide(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID id,
                                                     @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                     @Valid @RequestBody MembershipDecisionRequest request) {
        long version = IfMatch.requireVersion(ifMatch);
        MembershipResponse decided = membershipService.decide(user.userId(), id, version, request);
        return ResponseEntity.ok().eTag(IfMatch.etag(decided.version())).body(decided);
    }
}
