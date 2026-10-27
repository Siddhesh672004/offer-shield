package com.offershield.support;

/**
 * The Groq stand-in for integration tests ({@link IntegrationTest} points {@code offershield.ai.base-url} at it and
 * sets a fake key), shared per JVM. Its default replies are valid and empty, so analyses use AI without finding
 * anything; a test that changes the replies calls {@link GroqStub#reset()} afterwards. Rate limits and bad keys are
 * tested on their own chains (unit tests), never here, because the application's chain remembers resting models.
 */
public final class AiStubs {

    public static final GroqStub GROQ = GroqStub.start();
    public static final String TEST_KEY = "it-test-key-not-a-secret";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(GROQ::close, "groq-stub-stop"));
    }

    private AiStubs() {
    }
}
