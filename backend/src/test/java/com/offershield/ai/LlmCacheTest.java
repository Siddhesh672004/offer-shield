package com.offershield.ai;

import com.offershield.support.AiStubs;
import com.offershield.support.GroqStub;
import com.offershield.support.GroqStub.Reply;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §8.5 / D-06 (MySQL, V12): an extraction answer is kept in {@code llm_cache} under
 * sha256(task + prompt version + masked letter), masked as it came back, so the same letter is never sent twice.
 */
class LlmCacheTest extends IntegrationTest {

    private static final String ANSWER = "{\"companyName\":null,\"recruiterName\":null,\"ctc\":null,\"joiningDate\":null,"
            + "\"phoneNumbers\":[\"[PHONE_1]\"],\"upiIds\":[],\"paymentMentions\":[\"Pay Rs. 5,000 deposit today\"]}";

    @Autowired
    private ExtractionService extraction;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void resetStub() {
        AiStubs.GROQ.reset();
    }

    @Test
    void secondCall_noProviderCall() {
        String marker = marker();
        String letter = "Dear Candidate, reference " + marker + ". Pay Rs. 5,000 deposit today. Call +91 99999 03333.";
        AiStubs.GROQ.respond(request -> request.body().contains(marker)
                ? Reply.content(ANSWER) : Reply.content(GroqStub.EMPTY_EXTRACTION));

        Extraction first = extraction.extract(letter).orElseThrow();
        Extraction second = extraction.extract(letter).orElseThrow();

        assertThat(AiStubs.GROQ.requests().stream().filter(request -> request.body().contains(marker)).count())
                .isEqualTo(1);
        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        assertThat(second.phoneNumbers()).containsExactly("+91 99999 03333").isEqualTo(first.phoneNumbers());
        assertThat(second.paymentMentions()).containsExactly("Pay Rs. 5,000 deposit today");

        Map<String, Object> row = jdbc.queryForMap("SELECT task, provider, model, response_json FROM llm_cache "
                + "WHERE cache_key = ?", ExtractionService.cacheKey(Redactor.mask(Redactor.clean(letter)).text()));
        assertThat(row.get("task")).isEqualTo("extract");
        assertThat(row.get("provider")).isEqualTo("groq");
        assertThat(row.get("model")).isEqualTo("openai/gpt-oss-120b");
        assertThat((String) row.get("response_json")).contains("[PHONE_1]").doesNotContain("99999");
    }

    @Test
    void unparseableCachedRow_loggedAndReplacedByTheNextAnswer() {
        // P13 (silent failure 12): a stored answer that no longer parses (e.g. after a parser change) must not stay a
        // silent miss forever: it is logged and replaced by the next valid answer.
        String marker = marker();
        String letter = "Dear Candidate, reference " + marker + ". Pay Rs. 5,000 deposit today. Call +91 99999 03333.";
        String key = ExtractionService.cacheKey(Redactor.mask(Redactor.clean(letter)).text());
        jdbc.update("INSERT INTO llm_cache (cache_key, task, response_json, provider, model) VALUES (?, ?, ?, ?, ?)",
                key, "extract", "{\"companyName\":", "groq", "old-model");
        AiStubs.GROQ.respond(request -> request.body().contains(marker)
                ? Reply.content(ANSWER) : Reply.content(GroqStub.EMPTY_EXTRACTION));

        Extraction fresh;
        java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> events;
        try (com.offershield.support.LogCapture logs = com.offershield.support.LogCapture.of(ExtractionService.class)) {
            fresh = extraction.extract(letter).orElseThrow();
            events = logs.events();
        }
        Extraction next = extraction.extract(letter).orElseThrow();

        assertThat(fresh.fromCache()).isFalse();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("cached answer unusable");
        });
        assertThat(jdbc.queryForObject("SELECT response_json FROM llm_cache WHERE cache_key = ?", String.class, key))
                .contains("[PHONE_1]");
        assertThat(next.fromCache()).isTrue();
        assertThat(AiStubs.GROQ.requests().stream().filter(request -> request.body().contains(marker)).count())
                .isEqualTo(1);
    }

    @Test
    void otherLetter_newProviderCall() {
        String marker = marker();
        extraction.extract("Dear Candidate, reference " + marker + "-1. Welcome aboard.");
        extraction.extract("Dear Candidate, reference " + marker + "-2. Welcome aboard.");

        assertThat(AiStubs.GROQ.requests().stream().filter(request -> request.body().contains(marker)).count())
                .isEqualTo(2);
    }

    /** Letters only: a run of ten digits in a random UUID would be masked as a number before sending. */
    private static String marker() {
        return "CACHE" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
    }
}
