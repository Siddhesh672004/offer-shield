package com.offershield.scamreport;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.web.IfMatch;
import com.offershield.submission.PdfLink;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin's scam-report moderation (PRD §6.4 steps 5–7, §11; D-05): the queue with snapshot evidence, the decision
 * with {@code If-Match}, and a short-lived PDF link. ADMIN only (also enforced for {@code /api/admin/**} in
 * {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/admin/scam-reports")
@PreAuthorize("hasRole('ADMIN')")
public class AdminScamReportController {

    private final ScamReportService scamReports;

    public AdminScamReportController(ScamReportService scamReports) {
        this.scamReports = scamReports;
    }

    @GetMapping
    public List<AdminScamReportResponse> list(@RequestParam(defaultValue = "OPEN") ScamReportStatus status) {
        return scamReports.list(status);
    }

    @PostMapping("/{id}/decision")
    public ResponseEntity<AdminScamReportResponse> decide(@AuthenticationPrincipal AppOidcUser user,
                                                          @PathVariable UUID id,
                                                          @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                          String ifMatch,
                                                          @Valid @RequestBody ScamReportDecisionRequest request) {
        long version = IfMatch.requireVersion(ifMatch);
        AdminScamReportResponse decided = scamReports.decide(user.userId(), id, version, request.decision());
        return ResponseEntity.ok().eTag(IfMatch.etag(decided.version())).body(decided);
    }

    @GetMapping("/{id}/pdf-link")
    public PdfLink pdfLink(@PathVariable UUID id) {
        return scamReports.pdfLink(id);
    }
}
