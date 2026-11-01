package com.offershield.analysis;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.logging.LogContext;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code POST /api/reports/{submissionId}/explain}: "Explain simply" (PRD §4 F4, §11; P7-03). Owner only, like the
 * report itself: someone else's, unknown or not-yet-analysed report → the same 404. A state-changing POST, so it needs
 * the XSRF token (CSRF {@code spa()}). Returns {@link ExplanationResponse}; it never fails because the AI is down (a
 * template from the signals is returned instead).
 */
@RestController
@RequestMapping("/api/reports")
public class ExplainController {

    private final ReportExplanationService explanations;

    public ExplainController(ReportExplanationService explanations) {
        this.explanations = explanations;
    }

    @PostMapping("/{submissionId}/explain")
    public ExplanationResponse explain(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID submissionId) {
        MDC.put(LogContext.SUBMISSION_ID, submissionId.toString());
        return explanations.explain(user.userId(), submissionId);
    }
}
