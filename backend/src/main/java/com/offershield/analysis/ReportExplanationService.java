package com.offershield.analysis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.offershield.ai.ExplainService;
import com.offershield.ai.Explanation;
import com.offershield.common.web.TooManyRequestsException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * "Explain simply" for one report (P7-03, PRD §4 F4, §8.5 "cached too"): owner check and the saved explanation first
 * (one short read), then the AI outside any transaction, then one short write. An AI-written explanation is saved in
 * {@code report.explanation} and returned on every later call without asking the AI again; a template (AI unavailable)
 * is returned but not saved, so a later call can still get an AI-written one.
 *
 * <p>P13 (security review S3), so one account cannot use up the shared Groq quota, all in memory (single instance,
 * D-01):
 * <ul>
 *   <li>parallel calls for the same report share one AI call (single flight);</li>
 *   <li>a template answer is reused for {@link #TEMPLATE_TTL}, so retries do not ask Groq again meanwhile;</li>
 *   <li>at most {@value #MAX_AI_EXPLANATIONS} AI explanations per user in {@link #LIMIT_WINDOW} (429
 *       {@code EXPLAIN_LIMIT} with Retry-After). A saved explanation, a reused template and joining a call already
 *       running cost nothing.</li>
 * </ul>
 */
@Service
public class ReportExplanationService {

    static final Duration TEMPLATE_TTL = Duration.ofMinutes(10);
    static final int MAX_AI_EXPLANATIONS = 10;
    static final Duration LIMIT_WINDOW = Duration.ofMinutes(10);
    private static final long MAX_TRACKED = 100_000;

    private final ReportService reports;
    private final ExplainService explainService;
    private final Cache<UUID, ExplanationResponse> templates;
    private final Cache<UUID, Bucket> limits;
    private final ConcurrentMap<UUID, CompletableFuture<ExplanationResponse>> running = new ConcurrentHashMap<>();

    @Autowired
    public ReportExplanationService(ReportService reports, ExplainService explainService) {
        this(reports, explainService, Ticker.systemTicker());
    }

    ReportExplanationService(ReportService reports, ExplainService explainService, Ticker ticker) {
        this.reports = reports;
        this.explainService = explainService;
        this.templates = Caffeine.newBuilder().expireAfterWrite(TEMPLATE_TTL).maximumSize(MAX_TRACKED).ticker(ticker)
                .build();
        this.limits = Caffeine.newBuilder().expireAfterAccess(LIMIT_WINDOW).maximumSize(MAX_TRACKED).build();
    }

    public ExplanationResponse explain(UUID ownerUserId, UUID submissionId) {
        ReportService.ExplainSource source = reports.explainSourceForOwner(ownerUserId, submissionId);
        if (source.explanation() != null) {
            return new ExplanationResponse(submissionId, source.explanation(), true);
        }
        ExplanationResponse template = templates.getIfPresent(submissionId);
        if (template != null) {
            return template;
        }
        CompletableFuture<ExplanationResponse> mine = new CompletableFuture<>();
        CompletableFuture<ExplanationResponse> other = running.putIfAbsent(submissionId, mine);
        if (other != null) {
            return join(other); // another click on this report is already asking the AI
        }
        try {
            ExplanationResponse response = askAi(ownerUserId, submissionId, source);
            mine.complete(response);
            return response;
        } catch (RuntimeException | Error ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            running.remove(submissionId, mine);
        }
    }

    private ExplanationResponse askAi(UUID ownerUserId, UUID submissionId, ReportService.ExplainSource source) {
        consumeAiExplanation(ownerUserId);
        Explanation explanation = explainService.explain(source.facts());
        if (!explanation.aiWritten()) {
            ExplanationResponse response = new ExplanationResponse(submissionId, explanation.text(), false);
            templates.put(submissionId, response);
            return response;
        }
        return new ExplanationResponse(submissionId, reports.saveExplanation(submissionId, explanation.text()), true);
    }

    private void consumeAiExplanation(UUID ownerUserId) {
        Bucket bucket = limits.get(ownerUserId, id -> Bucket.builder()
                .addLimit(limit -> limit.capacity(MAX_AI_EXPLANATIONS).refillGreedy(MAX_AI_EXPLANATIONS, LIMIT_WINDOW))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new TooManyRequestsException("EXPLAIN_LIMIT",
                    "You can ask for up to 10 summaries every 10 minutes. Please wait a few minutes and try again.",
                    Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }

    private static ExplanationResponse join(CompletableFuture<ExplanationResponse> other) {
        try {
            return other.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }
}
