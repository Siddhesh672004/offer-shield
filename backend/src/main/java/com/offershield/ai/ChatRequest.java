package com.offershield.ai;

import java.util.Objects;

/**
 * One chat-completion request, the same for every model of the chain (PRD §8.6): a fixed system prompt, the user
 * message (the masked letter or report facts inside delimiters, never in the system prompt) and the output-token cap.
 * {@code task} names the job in logs ("extract", "explain"). {@link #toString()} never shows the prompts.
 */
public record ChatRequest(String task, String systemPrompt, String userMessage, int maxCompletionTokens) {

    public ChatRequest {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userMessage, "userMessage");
        if (maxCompletionTokens < 1 || maxCompletionTokens > AiProperties.MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxCompletionTokens must be 1-" + AiProperties.MAX_OUTPUT_TOKENS);
        }
    }

    @Override
    public String toString() {
        return "ChatRequest[task=" + task + ", redacted]";
    }
}
