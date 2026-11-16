package com.offershield.scamreport;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.logging.LogContext;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code POST /api/scam-reports} (USER, own analysed submission only) → 201; {@code GET /api/scam-reports} lists the
 * signed-in user's own reports (PRD §6.4, §11). The reporter is always the session user.
 */
@RestController
@RequestMapping("/api/scam-reports")
public class ScamReportController {

    private final ScamReportService scamReports;

    public ScamReportController(ScamReportService scamReports) {
        this.scamReports = scamReports;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ScamReportResponse> report(@AuthenticationPrincipal AppOidcUser user,
                                                     @Valid @RequestBody CreateScamReportRequest request) {
        MDC.put(LogContext.SUBMISSION_ID, request.submissionId().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(scamReports.report(user.userId(), request));
    }

    @GetMapping
    public List<ScamReportResponse> mine(@AuthenticationPrincipal AppOidcUser user) {
        return scamReports.mine(user.userId());
    }
}
