package com.offershield.ai;

/** One OpenAI-compatible chat-completion call to one model (implemented by {@link GroqClient}). Never throws. */
public interface ChatProvider {

    /** The provider's name for logs and the cache ("groq"). */
    String name();

    ProviderReply complete(String model, ChatRequest request);
}
