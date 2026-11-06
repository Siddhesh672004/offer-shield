package com.offershield.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Groq model chain (PRD §8.2, §15; D-11: Groq only). {@code apiKeys} is {@code GROQ_API_KEYS} (comma-separated;
 * Groq limits apply per organization, so only the first key is used); blank means AI is off and every report says
 * "AI unavailable". {@code chain} is {@code AI_CHAIN}: {@code groq:<model>} entries in order; entries for any other
 * provider are ignored. {@code timeout} bounds each model call (CLAUDE.md: Groq 10 s). On a 429 without usable headers
 * a model rests for {@code defaultRateLimitWait}; an unknown model rests for {@code unknownModelWait}. The letter sent
 * is capped at {@code maxLetterChars} (PRD §8.5); output tokens are capped per task (04 P7-01: at most 1,500).
 * {@link #toString()} never shows the keys.
 */
@ConfigurationProperties("offershield.ai")
public record AiProperties(List<String> apiKeys,
                           @DefaultValue(AiProperties.DEFAULT_CHAIN) String chain,
                           @DefaultValue("https://api.groq.com/openai/v1") URI baseUrl,
                           @DefaultValue("10s") Duration timeout,
                           @DefaultValue("60s") Duration defaultRateLimitWait,
                           @DefaultValue("1h") Duration unknownModelWait,
                           @DefaultValue("6000") int maxLetterChars,
                           @DefaultValue("1200") int extractionMaxTokens,
                           @DefaultValue("800") int explainMaxTokens) {

    public static final String DEFAULT_CHAIN =
            "groq:openai/gpt-oss-120b,groq:openai/gpt-oss-20b,groq:qwen/qwen3.8-27b";
    static final int MAX_OUTPUT_TOKENS = 1500;
    private static final String GROQ = "groq:";

    public AiProperties {
        apiKeys = apiKeys == null ? List.of()
                : apiKeys.stream().filter(key -> key != null && !key.isBlank()).map(String::strip).toList();
        chain = chain == null || chain.isBlank() ? DEFAULT_CHAIN : chain;
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("offershield.ai.timeout must be positive");
        }
        if (extractionMaxTokens < 1 || extractionMaxTokens > MAX_OUTPUT_TOKENS
                || explainMaxTokens < 1 || explainMaxTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("offershield.ai max tokens must be 1-" + MAX_OUTPUT_TOKENS);
        }
        if (maxLetterChars < 500) {
            throw new IllegalArgumentException("offershield.ai.max-letter-chars must be at least 500");
        }
    }

    /** The Groq models of the chain, in order, without duplicates. */
    public List<String> models() {
        List<String> models = new ArrayList<>();
        Arrays.stream(chain.split(","))
                .map(String::strip)
                .filter(entry -> entry.toLowerCase(Locale.ROOT).startsWith(GROQ))
                .map(entry -> entry.substring(GROQ.length()).strip())
                .filter(model -> !model.isEmpty() && !models.contains(model))
                .forEach(models::add);
        return List.copyOf(models);
    }

    /** Chain entries that are not {@code groq:<model>} (logged once at startup, then ignored). */
    List<String> ignoredEntries() {
        return Arrays.stream(chain.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty() && !entry.toLowerCase(Locale.ROOT).startsWith(GROQ))
                .toList();
    }

    /** The key sent to Groq, or {@code null} when AI is off. */
    String apiKey() {
        return apiKeys.isEmpty() ? null : apiKeys.get(0);
    }

    public boolean enabled() {
        return apiKey() != null && !models().isEmpty();
    }

    @Override
    public String toString() {
        return "AiProperties[apiKeys=" + apiKeys.size() + " redacted, chain=" + chain + ", baseUrl=" + baseUrl
                + ", timeout=" + timeout + "]";
    }
}
