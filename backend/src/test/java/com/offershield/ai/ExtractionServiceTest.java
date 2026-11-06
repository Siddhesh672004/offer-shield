package com.offershield.ai;

import com.offershield.support.GroqStub;
import com.offershield.support.GroqStub.Reply;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PRD §7 ("AI never creates evidence"), §8.5: what the analysis gets back from an extraction. Placeholders are mapped
 * back locally; values the model could not have read in the letter are dropped; a cached answer is reused without a
 * provider call; failures give an empty result, never an exception. Fake letter, fake values.
 */
class ExtractionServiceTest {

    private static final String MODEL = "openai/gpt-oss-120b";
    private static final String LETTER = """
            Dear Rahul Sharma,
            Northwind Careers Pvt Ltd offers you the role of Data Analyst at a CTC of 6,00,000 per annum.
            Joining date: 1 November 2026. Call +91 98765 43210.
            Pay the refundable deposit of Rs. 5,000 to northwind.pay@okaxis before joining.
            Regards, Mr. Arjun Mehta""";

    private final GroqStub stub = GroqStub.start();
    private final LlmCache cache = mock(LlmCache.class);

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void placeholdersMappedBackLocally_groundedValuesKept() {
        stub.replyFor(MODEL, Reply.content("""
                {"companyName":"Northwind Careers Pvt Ltd","recruiterName":"[NAME_2]","ctc":"6,00,000 per annum",
                 "joiningDate":"1 November 2026","phoneNumbers":["[PHONE_1]"],"upiIds":["[UPI_1]"],
                 "paymentMentions":["Pay the refundable deposit of Rs. 5,000 to [UPI_1] before joining."]}"""));

        Extraction extraction = service(true).extract(LETTER).orElseThrow();

        assertThat(extraction.companyName()).isEqualTo("Northwind Careers Pvt Ltd");
        assertThat(extraction.recruiterName()).as("a placeholder is never stored as a text field").isNull();
        assertThat(extraction.ctc()).isEqualTo("6,00,000 per annum");
        assertThat(extraction.joiningDate()).isEqualTo("1 November 2026");
        assertThat(extraction.phoneNumbers()).containsExactly("+91 98765 43210");
        assertThat(extraction.upiIds()).containsExactly("northwind.pay@okaxis");
        assertThat(extraction.paymentMentions())
                .containsExactly("Pay the refundable deposit of Rs. 5,000 to [UPI_1] before joining.");
        assertThat(extraction.model()).isEqualTo(MODEL);
        assertThat(extraction.fromCache()).isFalse();
    }

    @Test
    void ungroundedValues_dropped() {
        stub.replyFor(MODEL, Reply.content("""
                {"companyName":"Google India","recruiterName":"Sundar","ctc":"50 LPA","joiningDate":"tomorrow",
                 "phoneNumbers":["+91 90000 00000","[EMAIL_1]","[PHONE_7]"],"upiIds":["[UPI_9]","fake@ybl"],
                 "paymentMentions":["Transfer Rs. 9,999 now","fee","Pay   the REFUNDABLE deposit of Rs. 5,000"]}"""));

        Extraction extraction = service(true).extract(LETTER).orElseThrow();

        assertThat(extraction.companyName()).isNull();
        assertThat(extraction.recruiterName()).isNull();
        assertThat(extraction.ctc()).isNull();
        assertThat(extraction.joiningDate()).isNull();
        assertThat(extraction.phoneNumbers()).isEmpty();
        assertThat(extraction.upiIds()).isEmpty();
        assertThat(extraction.paymentMentions()).as("whitespace and case may differ; invented text may not")
                .containsExactly("Pay   the REFUNDABLE deposit of Rs. 5,000");
    }

    @Test
    void groundedWordsThatAreNotContacts_dropped() {
        stub.replyFor(MODEL, Reply.content("""
                {"companyName":"N","recruiterName":null,"ctc":null,"joiningDate":null,
                 "phoneNumbers":["Call","+91"],"upiIds":["refundable deposit","Rs. 5,000"],"paymentMentions":[]}"""));

        Extraction extraction = service(true).extract(LETTER).orElseThrow();

        assertThat(extraction.companyName()).as("too short to mean anything").isNull();
        assertThat(extraction.phoneNumbers()).isEmpty();
        assertThat(extraction.upiIds()).isEmpty();
    }

    @Test
    void answer_cachedMaskedOnly() {
        stub.replyFor(MODEL, Reply.content("""
                {"companyName":null,"recruiterName":null,"ctc":null,"joiningDate":null,
                 "phoneNumbers":["[PHONE_1]"],"upiIds":[],"paymentMentions":[]}"""));

        service(true).extract(LETTER);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(cache).put(eq(ExtractionService.cacheKey(Redactor.mask(Redactor.clean(LETTER)).text())),
                eq("extract"), json.capture(), eq("groq"), eq(MODEL));
        assertThat(json.getValue()).contains("[PHONE_1]").doesNotContain("98765");
    }

    @Test
    void cacheHit_noProviderCall() {
        when(cache.find(anyString())).thenReturn(Optional.of(GroqStub.EMPTY_EXTRACTION.replace("\"companyName\":null",
                "\"companyName\":\"Northwind Careers Pvt Ltd\"")));

        Extraction extraction = service(true).extract(LETTER).orElseThrow();

        assertThat(stub.requests()).isEmpty();
        assertThat(extraction.fromCache()).isTrue();
        assertThat(extraction.companyName()).isEqualTo("Northwind Careers Pvt Ltd");
        verify(cache, never()).put(any(), any(), any(), any(), any());
    }

    @Test
    void sameLetterOtherStudent_sameCacheKey() {
        String other = LETTER.replace("Rahul Sharma", "Ananya Rao").replace("98765 43210", "91234 56789");

        assertThat(ExtractionService.cacheKey(Redactor.mask(Redactor.clean(other)).text()))
                .isEqualTo(ExtractionService.cacheKey(Redactor.mask(Redactor.clean(LETTER)).text()))
                .hasSize(64);
    }

    @Test
    void disabled_emptyWithoutCallOrCache() {
        assertThat(service(false).extract(LETTER)).isEmpty();

        assertThat(stub.requests()).isEmpty();
        verifyNoInteractions(cache);
    }

    @Test
    void allModelsFail_empty() {
        stub.replyFor(MODEL, Reply.status(503, ""));

        assertThat(service(true).extract(LETTER)).isEmpty();
        verify(cache, never()).put(any(), any(), any(), any(), any());
    }

    @Test
    void unexpectedError_emptyAndClassOnlyLogged() {
        when(cache.find(anyString())).thenThrow(new IllegalStateException("SECRET-DETAIL"));

        try (LogCapture logs = LogCapture.of(ExtractionService.class)) {
            assertThat(service(true).extract(LETTER)).isEmpty();
            assertThat(logs.events()).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("IllegalStateException"));
            assertThat(logs.events()).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("SECRET-DETAIL"));
        }
    }

    private ExtractionService service(boolean withKey) {
        AiProperties properties = new AiProperties(withKey ? List.of("test-key") : List.of(), "groq:" + MODEL,
                URI.create(stub.baseUrl()), Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofHours(1), 6000,
                1200, 800);
        return new ExtractionService(new LlmChain(properties, new GroqClient(properties, JsonMapper.builder().build())),
                cache, properties);
    }
}
