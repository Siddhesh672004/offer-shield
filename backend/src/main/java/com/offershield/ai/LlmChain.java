package com.offershield.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The fallback chain of PRD §8.4 over the Groq models of {@code AI_CHAIN} (D-11), never cut:
 * <pre>
 * for each model:
 *     resting until T and now &lt; T → skip
 *     call (10 s timeout)
 *     200 + an answer the task's parser accepts → return it
 *     429                         → rest until retry-after / reset header / 60 s → next
 *     unknown or retired model    → rest 1 h → next
 *     401/403 (bad key)           → stop: every model shares the key
 *     5xx, timeout, refused, unreadable or invalid answer → next (no retries, no storm)
 * all failed → empty: the analysis continues and the report says "AI unavailable"
 * </pre>
 * With no key the chain is off: no call is made and one WARN line at startup says so (P13: a production install
 * without AI is a misconfiguration worth noticing). A parser that throws (a bug, not bad model output) is logged at
 * WARN with its exception class and the next model is tried.
 *
 * <p>Resting models are remembered in a small map (one entry per model of the chain, so it cannot grow). Logs (D-27)
 * name the task, model, outcome, duration and token counts (V-09), never prompts, answers or the key.
 */
@Component
public class LlmChain {

    private static final Logger log = LoggerFactory.getLogger(LlmChain.class);

    private final ChatProvider provider;
    private final List<String> models;
    private final boolean enabled;
    private final Duration unknownModelWait;
    private final Clock clock;
    private final Map<String, Instant> restingUntil = new ConcurrentHashMap<>();

    @Autowired
    public LlmChain(AiProperties properties, ChatProvider provider) {
        this(properties, provider, Clock.systemUTC());
    }

    LlmChain(AiProperties properties, ChatProvider provider, Clock clock) {
        this.provider = provider;
        this.models = properties.models();
        this.enabled = properties.enabled();
        this.unknownModelWait = properties.unknownModelWait();
        this.clock = clock;
        properties.ignoredEntries().forEach(entry -> log.warn(
                "ai chain entry ignored: only groq:<model> entries are supported (D-11) provider={}", providerOf(entry)));
        if (!enabled) {
            log.warn("ai not configured (GROQ_API_KEYS blank or AI_CHAIN has no groq model): reports run rule-only "
                    + "and say \"AI unavailable\"");
        } else {
            log.info("ai enabled provider={} models={}{}", provider.name(), models, properties.apiKeys().size() > 1
                    ? " (only the first key is used: Groq limits apply per organization)" : "");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Asks the models in order until one gives an answer {@code parser} accepts.
     *
     * @param parser turns the assistant message into the task's result, or empty when it is not valid
     * @return empty when AI is off or every model failed, was resting or answered invalidly
     */
    public <T> Optional<LlmAnswer<T>> complete(ChatRequest request, Function<String, Optional<T>> parser) {
        if (!enabled) {
            return Optional.empty();
        }
        for (String model : models) {
            Instant until = restingUntil.get(model);
            if (until != null) {
                if (clock.instant().isBefore(until)) {
                    log.debug("ai model resting task={} model={}", request.task(), model);
                    continue;
                }
                restingUntil.remove(model, until);
            }
            long start = System.nanoTime();
            ProviderReply reply = provider.complete(model, request);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            switch (reply.outcome()) {
                case OK -> {
                    Optional<T> parsed = parse(parser, reply.content(), request.task(), model);
                    ProviderReply.Usage usage = reply.usage();
                    log.info("ai call task={} model={} outcome={} durationMs={} promptTokens={} completionTokens={} "
                                    + "reasoningTokens={}", request.task(), model, parsed.isPresent() ? "ok" : "invalid-output",
                            durationMs, usage.promptTokens(), usage.completionTokens(), usage.reasoningTokens());
                    if (parsed.isPresent()) {
                        return Optional.of(new LlmAnswer<>(parsed.get(), provider.name(), model));
                    }
                }
                case RATE_LIMITED -> {
                    restingUntil.put(model, clock.instant().plus(reply.restFor()));
                    log.info("ai call task={} model={} outcome=rate-limited durationMs={} restSeconds={}",
                            request.task(), model, durationMs, reply.restFor().toSeconds());
                }
                case UNKNOWN_MODEL -> {
                    restingUntil.put(model, clock.instant().plus(unknownModelWait));
                    log.warn("ai call task={} model={} outcome=unknown-model status={}: check AI_CHAIN; skipped for {} min",
                            request.task(), model, reply.status(), unknownModelWait.toMinutes());
                }
                case AUTH_FAILED -> {
                    log.warn("ai key rejected (status={}) task={} model={}: check GROQ_API_KEYS; this analysis "
                            + "continues without AI", reply.status(), request.task(), model);
                    return Optional.empty();
                }
                case FAILED -> log.info("ai call task={} model={} outcome=failed reason={} durationMs={}",
                        request.task(), model, reply.reason(), durationMs);
            }
        }
        log.info("ai unavailable task={}: every model failed or is resting", request.task());
        return Optional.empty();
    }

    private static <T> Optional<T> parse(Function<String, Optional<T>> parser, String content, String task,
                                         String model) {
        try {
            Optional<T> parsed = parser.apply(content);
            return parsed == null ? Optional.empty() : parsed;
        } catch (RuntimeException ex) {
            // A parser rejects bad output by returning empty; throwing is a bug of ours. Class only (D-27).
            log.warn("ai answer parser failed task={} model={} error={}", task, model, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String providerOf(String entry) {
        int colon = entry.indexOf(':');
        String name = colon < 0 ? entry : entry.substring(0, colon);
        return name.length() > 20 ? name.substring(0, 20) : name;
    }
}
