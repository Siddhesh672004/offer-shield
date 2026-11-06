package com.offershield.ai;

/** A parsed, valid answer from the chain and where it came from (for the cache and logs). */
public record LlmAnswer<T>(T value, String provider, String model) {

    @Override
    public String toString() {
        return "LlmAnswer[provider=" + provider + ", model=" + model + ", redacted]";
    }
}
