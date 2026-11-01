package com.offershield.analysis;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.logging.LogContext;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code GET /api/reports/{submissionId}}: the owner's report (PRD §11; D-04 keeps it owner-only: TPOs and the admin
 * see evidence through review cases and scam reports). Not ready yet, someone else's or unknown → the same 404. The
 * frontend polls {@code GET /api/submissions/{id}} first and asks for the report once it is READY or PARTIAL (D-14).
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/{submissionId}")
    public ReportResponse get(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID submissionId) {
        MDC.put(LogContext.SUBMISSION_ID, submissionId.toString());
        return reportService.getForOwner(user.userId(), submissionId);
    }
}
