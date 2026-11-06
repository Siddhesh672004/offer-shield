package com.offershield.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §8.6: the model's answer must parse into the fixed {@link ExtractionResult} record; anything else (unknown or
 * duplicate keys, wrong types, over-long values, trailing text, not an object) is discarded as a whole.
 */
class ExtractionResultParsingTest {

    private static final String VALID = """
            {"companyName":"Northwind Analytics","recruiterName":"[NAME_2]","ctc":"6,00,000 per annum",
             "joiningDate":"1 November 2026","phoneNumbers":["[PHONE_1]"],"upiIds":["[UPI_1]"],
             "paymentMentions":["Pay Rs. 5,000 refundable security deposit"]}""";

    @Test
    void valid_parsed() {
        assertThat(ExtractionResult.parse(VALID)).hasValueSatisfying(result -> {
            assertThat(result.companyName()).isEqualTo("Northwind Analytics");
            assertThat(result.recruiterName()).isEqualTo("[NAME_2]");
            assertThat(result.ctc()).isEqualTo("6,00,000 per annum");
            assertThat(result.joiningDate()).isEqualTo("1 November 2026");
            assertThat(result.phoneNumbers()).containsExactly("[PHONE_1]");
            assertThat(result.upiIds()).containsExactly("[UPI_1]");
            assertThat(result.paymentMentions()).containsExactly("Pay Rs. 5,000 refundable security deposit");
        });
    }

    @Test
    void missingKeysNullsAndBlanks_defaulted() {
        assertThat(ExtractionResult.parse("{\"companyName\":\"  \",\"ctc\":null,\"upiIds\":null}"))
                .hasValueSatisfying(result -> {
                    assertThat(result.companyName()).isNull();
                    assertThat(result.ctc()).isNull();
                    assertThat(result.phoneNumbers()).isEmpty();
                    assertThat(result.upiIds()).isEmpty();
                    assertThat(result.paymentMentions()).isEmpty();
                });
    }

    @Test
    void unknownField_rejected() {
        assertThat(ExtractionResult.parse("{\"companyName\":\"Northwind\",\"score\":0}")).isEmpty();
        assertThat(ExtractionResult.parse("{\"band\":\"LOW\",\"safe\":true}")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"companyName\":5}", "{\"companyName\":{\"name\":\"x\"}}", "{\"phoneNumbers\":\"[PHONE_1]\"}",
            "{\"paymentMentions\":[1]}", "{\"upiIds\":[[\"x\"]]}", "{\"ctc\":true}", "{\"paymentMentions\":[null]}"})
    void wrongType_rejected(String json) {
        assertThat(ExtractionResult.parse(json)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "null", "[]", "\"text\"", "42", "not json", "```json\n{}\n```",
            "{\"companyName\":\"a\"} trailing", "{\"companyName\":\"a\",\"companyName\":\"b\"}", "{\"companyName\":"})
    void notOneCleanObject_rejected(String json) {
        assertThat(ExtractionResult.parse(json)).isEmpty();
    }

    @Test
    void null_rejected() {
        assertThat(ExtractionResult.parse(null)).isEmpty();
    }

    @Test
    void overLong_rejected() {
        assertThat(ExtractionResult.parse("{\"companyName\":\"" + "x".repeat(201) + "\"}")).isEmpty();
        assertThat(ExtractionResult.parse("{\"paymentMentions\":[\"" + "x".repeat(301) + "\"]}")).isEmpty();
        assertThat(ExtractionResult.parse("{\"phoneNumbers\":[" + "\"1\",".repeat(10) + "\"1\"]}")).isEmpty();
        assertThat(ExtractionResult.parse("{\"companyName\":\"" + "x".repeat(200) + "\"}")).isPresent();
    }

    @Test
    void toJson_roundTrips() {
        ExtractionResult result = ExtractionResult.parse(VALID).orElseThrow();

        assertThat(ExtractionResult.parse(result.toJson())).contains(result);
        assertThat(new ExtractionResult(null, null, null, null, List.of(), List.of(), List.of()).toJson())
                .contains("\"companyName\":null");
    }
}
