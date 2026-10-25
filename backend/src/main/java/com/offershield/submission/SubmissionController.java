package com.offershield.submission;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.logging.LogContext;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/submissions} (multipart, {@code Idempotency-Key}) → 202; owner-only reads (PRD §11). The owner is
 * always the session user; another user's submission is a 404 (PRD §14.3).
 */
@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SubmissionAccepted> submit(@AuthenticationPrincipal AppOidcUser user,
                                                     @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                                     @Valid @ModelAttribute SubmissionForm form) {
        SubmissionAccepted accepted = submissionService.submit(user.userId(), form.toCommand(key));
        MDC.put(LogContext.SUBMISSION_ID, accepted.submissionId().toString());
        return ResponseEntity.accepted()
                .location(URI.create("/api/submissions/" + accepted.submissionId()))
                .body(accepted);
    }

    @GetMapping
    public List<SubmissionResponse> list(@AuthenticationPrincipal AppOidcUser user) {
        return submissionService.list(user.userId());
    }

    @GetMapping("/{id}")
    public SubmissionResponse get(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID id) {
        MDC.put(LogContext.SUBMISSION_ID, id.toString());
        return submissionService.get(user.userId(), id);
    }
}
