package com.offershield.review;

import com.offershield.auth.AppOidcUser;
import com.offershield.common.logging.LogContext;
import com.offershield.common.web.IfMatch;
import com.offershield.submission.PdfLink;
import jakarta.validation.Valid;
import org.slf4j.MDC;
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

/**
 * Review cases (PRD §4 F5, §6.5, §11): the student asks ({@code POST /api/reviews}, USER) and lists their own cases;
 * the TPO of the case's college sees the queue and one case with its evidence, decides with {@code If-Match}, and gets
 * a short-lived PDF link (D-05). A wrong role is 403; another college's case is 404.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ReviewCaseResponse> request(@AuthenticationPrincipal AppOidcUser user,
                                                      @Valid @RequestBody CreateReviewRequest request) {
        MDC.put(LogContext.SUBMISSION_ID, request.submissionId().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(reviews.request(user.userId(), request.submissionId()));
    }

    @GetMapping
    public List<ReviewCaseResponse> mine(@AuthenticationPrincipal AppOidcUser user) {
        return reviews.mine(user.userId());
    }

    @GetMapping("/queue")
    @PreAuthorize("hasRole('TPO')")
    public List<ReviewCaseDetail> queue(@AuthenticationPrincipal AppOidcUser user) {
        return reviews.queue(user.userId());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('TPO')")
    public ReviewCaseDetail get(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID id) {
        return reviews.get(user.userId(), id);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('TPO')")
    public ResponseEntity<ReviewCaseDetail> decide(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID id,
                                                   @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                   String ifMatch,
                                                   @Valid @RequestBody ReviewDecisionRequest request) {
        long version = IfMatch.requireVersion(ifMatch);
        ReviewCaseDetail decided = reviews.decide(user.userId(), id, version, request);
        return ResponseEntity.ok().eTag(IfMatch.etag(decided.version())).body(decided);
    }

    @GetMapping("/{id}/pdf-link")
    @PreAuthorize("hasRole('TPO')")
    public PdfLink pdfLink(@AuthenticationPrincipal AppOidcUser user, @PathVariable UUID id) {
        return reviews.pdfLink(user.userId(), id);
    }
}
