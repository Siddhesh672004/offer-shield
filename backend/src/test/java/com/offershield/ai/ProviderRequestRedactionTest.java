package com.offershield.ai;

import com.offershield.support.GroqStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PRD §8.5, §8.6 + D-07 (05 §2.2): the exact bytes that reach the provider (a local stub) contain no planted name,
 * phone number, email address, UPI ID or ID number; the letter sits only in the user message between the per-request
 * BEGIN/END markers, and the system prompt is the fixed one. Fake letter, fake values.
 */
class ProviderRequestRedactionTest {

    private static final String LETTER = """
            To,
            Kumari Sneha Iyer

            Dear Rahul Sharma,
            Candidate Name: Priya Verma
            Mr. Arjun Mehta (HR) will call you on +91 98765 43210 or 022-2345 6789.
            Email rahul.sharma92@gmail.com or write to hr.desk@northwind-careers.in.
            Pay the refundable deposit of Rs. 5,000 to northwind.pay@okaxis.
            Aadhaar 2345 6789 0123. Welcome to Northwind Careers Pvt Ltd.
            """;
    private static final List<String> PLANTED = List.of("Sneha", "Iyer", "Rahul", "Sharma", "Priya", "Verma",
            "Arjun", "Mehta", "98765", "43210", "2345 6789", "rahul.sharma92", "gmail.com", "hr.desk",
            "northwind-careers.in", "northwind.pay", "okaxis", "0123");

    private final GroqStub stub = GroqStub.start();

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void requestBody_noRawPii() {
        service().extract(LETTER);

        assertThat(stub.requests()).hasSize(1);
        String rawBody = stub.requests().get(0).body();
        assertThat(rawBody).doesNotContain(PLANTED);
        JsonNode json = stub.requests().get(0).json();
        String system = json.path("messages").get(0).path("content").stringValue();
        String user = json.path("messages").get(1).path("content").stringValue();
        assertThat(user).doesNotContain(PLANTED);
        assertThat(user).contains("Dear [NAME_", "Candidate Name: [NAME_", "[PHONE_1]", "[PHONE_2]", "[EMAIL_1]",
                "[EMAIL_2]", "[UPI_1]", "[NUMBER_1]", "refundable deposit of Rs. 5,000", "Northwind Careers Pvt Ltd");
        assertThat(system).isEqualTo(ExtractionPrompt.SYSTEM).doesNotContain("deposit of Rs");
    }

    @Test
    void letter_onlyInUserMessage_betweenRandomMarkers() {
        service().extract(LETTER);
        service().extract(LETTER);

        List<String> markers = stub.requests().stream().map(request -> {
            String user = request.json().path("messages").get(1).path("content").stringValue();
            Matcher matcher = Pattern.compile("(?s)=====BEGIN LETTER ([0-9a-f]{16})=====\n(.*)\n=====END LETTER \\1=====$")
                    .matcher(user);
            assertThat(matcher.find()).as("letter between matching markers at the end of the user message").isTrue();
            assertThat(matcher.group(2)).contains("refundable deposit");
            return matcher.group(1);
        }).toList();
        assertThat(markers).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void longLetter_cappedBeforeSending() {
        service().extract("Dear Candidate, " + "pay the fee now. ".repeat(2_000));

        String user = stub.requests().get(0).json().path("messages").get(1).path("content").stringValue();
        String letter = user.substring(user.indexOf("=====\n") + 6, user.lastIndexOf("\n=====END"));
        assertThat(letter).hasSizeLessThanOrEqualTo(6_000);
    }

    private ExtractionService service() {
        AiProperties properties = new AiProperties(List.of("test-key"), "groq:openai/gpt-oss-120b",
                URI.create(stub.baseUrl()), Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofHours(1), 6000,
                1200, 800);
        return new ExtractionService(new LlmChain(properties, new GroqClient(properties, JsonMapper.builder().build())),
                mock(LlmCache.class), properties);
    }
}
