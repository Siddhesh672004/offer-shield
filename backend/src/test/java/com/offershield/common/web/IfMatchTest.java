package com.offershield.common.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IfMatchTest {

    @ParameterizedTest
    @ValueSource(strings = {"3", "\"3\"", " \"3\" ", " 3 "})
    void requireVersion_plainOrQuoted_parsed(String header) {
        assertThat(IfMatch.requireVersion(header)).isEqualTo(3L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void requireVersion_missing_428(String header) {
        assertThatThrownBy(() -> IfMatch.requireVersion(header))
                .isInstanceOf(PreconditionRequiredException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(api.errorCode()).isEqualTo("PRECONDITION_REQUIRED");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "*", "W/\"3\"", "\"3\", \"4\"", "-1", "\"\"", "\"3", "3.0", "99999999999999999999"})
    void requireVersion_malformed_400(String header) {
        assertThatThrownBy(() -> IfMatch.requireVersion(header))
                .isInstanceOf(BadRequestException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.errorCode()).isEqualTo("INVALID_IF_MATCH");
                });
    }

    @Test
    void etag_version_quoted() {
        assertThat(IfMatch.etag(7L)).isEqualTo("\"7\"");
    }
}
