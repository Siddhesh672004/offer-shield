package com.offershield.ai;

import com.offershield.support.GroqStub;
import com.offershield.support.GroqStub.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7-03 (PRD §4 F4, §8.1, §8.6): "Explain simply" sends the report's signals (masked), never the letter, inside
 * per-request markers; only a short summary that keeps the product's copy rules is accepted; otherwise, or without AI,
 * a fixed template built from the signals is returned. Fake values only.
 */
class ExplainPromptTest {

    private static final String MODEL = "openai/gpt-oss-120b";
    private static final String SUMMARY = "{\"summary\":\"This letter shows strong scam signs. It asks you to pay a "
            + "refundable deposit and pushes you to decide within 24 hours. One check could not run. Confirm the offer "
            + "through the company's official website or careers page, and never pay money to get a job.\"}";
    private static final ReportFacts FACTS = new ReportFacts("HIGH", "Strong scam indicators. Do not pay anything.", 10,
            true, List.of(
            new ReportFacts.Fact("C1", "Money request", "TRIGGERED", 0, "The letter asks you to pay money.",
                    "Dear Rahul Sharma, pay ₹5,000 refundable deposit to [UPI ID]", 1, false),
            new ReportFacts.Fact("C2", "Free email sender", "PASSED", 0, "The sender's address is not free email.",
                    null, 1, false),
            new ReportFacts.Fact("C5", "Young domain", "UNAVAILABLE", 0, "The registry gave no date.", null, 2, false),
            new ReportFacts.Fact("C9", "Pressure language", "TRIGGERED", 10, "The letter pushes you to decide fast.",
                    "“within 24 hours”", 1, true)));

    private final GroqStub stub = GroqStub.start();

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void aiAnswer_used() {
        stub.replyFor(MODEL, Reply.content(SUMMARY));

        Explanation explanation = service(true).explain(FACTS);

        assertThat(explanation.aiWritten()).isTrue();
        assertThat(explanation.text()).startsWith("This letter shows strong scam signs.");
    }

    @Test
    void request_signalsOnlyMasked_betweenMarkers() {
        stub.replyFor(MODEL, Reply.content(SUMMARY));

        service(true).explain(FACTS);

        String user = stub.requests().get(0).json().path("messages").get(1).path("content").stringValue();
        String system = stub.requests().get(0).json().path("messages").get(0).path("content").stringValue();
        assertThat(user).containsPattern("=====BEGIN REPORT [0-9a-f]{16}=====")
                .containsPattern("=====END REPORT [0-9a-f]{16}=====$")
                .contains("HIGH", "Strong scam indicators. Do not pay anything.", "Money request",
                        "The letter asks you to pay money.", "Young domain", "Pressure language", "AI-extracted",
                        "the letter itself")
                .doesNotContain("Rahul", "Sharma");
        assertThat(system).doesNotContain("Money request", "Rahul");
    }

    @Test
    void aiUnavailable_template() {
        Explanation explanation = service(false).explain(FACTS);

        assertThat(explanation.aiWritten()).isFalse();
        assertThat(explanation.text())
                .startsWith("Strong scam indicators. Do not pay anything.")
                .contains("Money request", "The letter asks you to pay money.", "Pressure language (AI-extracted)",
                        "could not run this time: Young domain", "official website or careers page")
                .doesNotContain("Free email sender");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void lowBandWithoutRedFlags_template() {
        ReportFacts low = new ReportFacts("LOW", "No major red flags found. This does not prove the offer is genuine.",
                0, false, List.of(new ReportFacts.Fact("C1", "Money request", "PASSED", 0, "No request for money.", null,
                1, false)));

        String text = ExplainService.template(low);

        assertThat(text).startsWith("No major red flags found. This does not prove the offer is genuine.")
                .contains("None of the checks found a red flag").doesNotContain("could not run");
        assertThat(text.toLowerCase()).doesNotContain("verified", "safe", "legit", "guarantee");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"summary\":\"This offer looks safe to accept. The company is real and the checks found nothing wrong at all.\"}",
            "{\"summary\":\"The company is verified. You can accept this offer and join on the given date without worry.\"}",
            "{\"summary\":\"This is a legitimate offer from a real employer, so go ahead and accept it today please.\"}",
            "{\"summary\":\"Joining is guaranteed. Pay the small deposit and you will receive your appointment soon.\"}",
            "{\"summary\":\"Call 98765 43210 or visit https://evil.example to confirm this offer before tomorrow.\"}",
            "{\"summary\":\"Too short.\"}",
            "{\"summary\":\"Strong scam signs here, please be careful with it and check the company website.\",\"band\":\"LOW\"}",
            "{\"text\":\"Strong scam signs here, please be careful with it and check the company website first.\"}",
            "not json"})
    void answerBreakingTheRules_template(String answer) {
        stub.replyFor(MODEL, Reply.content(answer));

        Explanation explanation = service(true).explain(FACTS);

        assertThat(explanation.aiWritten()).isFalse();
        assertThat(explanation.text()).startsWith("Strong scam indicators. Do not pay anything.");
    }

    @Test
    void parseSummary_allowsTheMandatedNotVerifiedLabel() {
        assertThat(ExplainService.parseSummary("{\"summary\":\"Two users reported this sender as a scam (not verified). "
                + "Check the company's official careers page and never pay money to get a job.\"}")).isPresent();
    }

    private ExplainService service(boolean withKey) {
        AiProperties properties = new AiProperties(withKey ? List.of("test-key") : List.of(), "groq:" + MODEL,
                URI.create(stub.baseUrl()), Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofHours(1), 6000,
                1200, 800);
        return new ExplainService(new LlmChain(properties, new GroqClient(properties, JsonMapper.builder().build())),
                properties);
    }
}
