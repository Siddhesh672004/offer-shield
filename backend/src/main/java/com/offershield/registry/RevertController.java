package com.offershield.registry;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.web.IfMatch;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The admin's Revert (D-13): {@code POST /api/admin/indicators/{id}/revert} with {@code If-Match: "<marker version>"}
 * sends a CONFIRMED marker back to UNVERIFIED (REJECTED when nobody reported it). Stale version → 409, not confirmed →
 * 409 {@code NOT_CONFIRMED}, unknown → 404; audited {@code INDICATOR_REVERTED} (R-E). ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/indicators")
@PreAuthorize("hasRole('ADMIN')")
public class RevertController {

    private final IndicatorService indicators;

    public RevertController(IndicatorService indicators) {
        this.indicators = indicators;
    }

    @PostMapping("/{id}/revert")
    public ResponseEntity<IndicatorView> revert(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID id,
                                                @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                String ifMatch) {
        long version = IfMatch.requireVersion(ifMatch);
        IndicatorView reverted = indicators.revert(id, version, user.userId());
        return ResponseEntity.ok().eTag(IfMatch.etag(reverted.version())).body(reverted);
    }
}
