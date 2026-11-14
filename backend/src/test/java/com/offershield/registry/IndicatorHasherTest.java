package com.offershield.registry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PRD §10.1, §14.6 (P9-02, D-18): phone numbers and UPI IDs become HMAC-SHA256 values with the server pepper, never
 * plain hashes. Every way of writing the same Indian number gives the same value; another pepper gives another value;
 * a missing or short pepper stops startup with a message that never shows it. Fake numbers only (05 §4).
 */
class IndicatorHasherTest {

    private static final String PEPPER = randomPepper();

    private final IndicatorHasher hasher = new IndicatorHasher(PEPPER);

    @Test
    void phoneFormats_sameHash() {
        List<String> writings = List.of("+91 99999 01234", "+91-99999-01234", "+919999901234", "09999901234",
                "9999901234", "99999 01234", "0091 99999 01234", "919999901234", "(+91) 99999-01234");

        List<Optional<String>> hashes = writings.stream().map(hasher::phone).toList();

        assertThat(hashes).allSatisfy(hash -> assertThat(hash).isPresent());
        assertThat(hashes.stream().map(Optional::get).distinct()).hasSize(1);
        assertThat(hashes.get(0).orElseThrow()).matches("[0-9a-f]{64}");
    }

    @Test
    void differentPepper_differentHash() {
        IndicatorHasher other = new IndicatorHasher(randomPepper());

        assertThat(other.phone("+91 99999 01234")).isNotEqualTo(hasher.phone("+91 99999 01234"));
        assertThat(other.upi("test.p9@upi")).isNotEqualTo(hasher.upi("test.p9@upi"));
    }

    @Test
    void differentNumbers_differentHash() {
        assertThat(hasher.phone("+91 99999 01234")).isNotEqualTo(hasher.phone("+91 99999 01235"));
    }

    @Test
    void notSha256OfTheNumber() {
        // PRD §10.1: a plain hash of a 10-digit number can be brute-forced; the stored value must need the pepper.
        String plain = com.offershield.common.Hashing.sha256Hex("+919999901234");

        assertThat(hasher.phone("+91 99999 01234")).isPresent().get().isNotEqualTo(plain);
    }

    @Test
    void upiCaseAndSpaces_sameHash() {
        assertThat(hasher.upi(" Test.P9@UPI ")).isPresent().isEqualTo(hasher.upi("test.p9@upi"));
    }

    @Test
    void phoneAndUpi_separateNamespaces() {
        assertThat(hasher.upi("9999901234@upi")).isNotEqualTo(hasher.phone("9999901234"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "12345", "call me", "+91 99999", "1234567890123456"})
    void notAPhone_empty(String value) {
        assertThat(hasher.phone(value)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "no-at-sign", "a@1bank", "hr@example.com"})
    void notAUpiId_empty(String value) {
        assertThat(hasher.upi(value)).isEmpty();
    }

    @Test
    void nullValues_empty() {
        assertThat(hasher.phone(null)).isEmpty();
        assertThat(hasher.upi(null)).isEmpty();
    }

    @Test
    void phoneNormalisation_indianNumbersBecomePlus91() {
        assertThat(IndicatorHasher.normalisePhone("099999 01234")).contains("+919999901234");
        assertThat(IndicatorHasher.normalisePhone("+44 20 7946 0000")).contains("+442079460000");
        assertThat(IndicatorHasher.normalisePhone("020 2612 3456")).contains("+912026123456");
    }

    @Test
    void missingPepper_failsFastWithoutValue() {
        assertThatThrownBy(() -> new IndicatorHasher(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INDICATOR_HMAC_PEPPER is not set");
        assertThatThrownBy(() -> new IndicatorHasher("  ")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INDICATOR_HMAC_PEPPER is not set");
    }

    @Test
    void shortPepper_failsFastWithoutValue() {
        String shortPepper = "short-pepper-zebra";

        assertThatThrownBy(() -> new IndicatorHasher(shortPepper)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(shortPepper));
    }

    @Test
    void applicationContext_withoutOrShortPepper_refusesToStart() {
        String shortPepper = "zebra-short-pepper";
        new ApplicationContextRunner()
                .withBean(IndicatorHasher.class)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("INDICATOR_HMAC_PEPPER is not set"));
        new ApplicationContextRunner()
                .withBean(IndicatorHasher.class)
                .withPropertyValues("offershield.registry.hmac-pepper=" + shortPepper)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("too short").hasMessageNotContaining(shortPepper));
        new ApplicationContextRunner()
                .withBean(IndicatorHasher.class)
                .withPropertyValues("offershield.registry.hmac-pepper=" + PEPPER)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(IndicatorHasher.class));
    }

    @Test
    void toString_neverShowsPepper() {
        assertThat(hasher.toString()).doesNotContain(PEPPER).contains("HMAC-SHA256");
    }

    static String randomPepper() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    // --- P13 (N2): masked hints a TPO or the admin may see; never the value ---

    @Test
    void phoneHint_lastFourDigitsOnly() {
        assertThat(hasher.phoneHint("+91 99999 01234")).contains("\u2022\u2022\u2022\u20221234");
        assertThat(hasher.phoneHint("099999-01234")).isEqualTo(hasher.phoneHint("9999901234"));
        assertThat(hasher.phoneHint("call me")).isEmpty();
        assertThat(hasher.phoneHint(null)).isEmpty();
    }

    @Test
    void upiHint_lastTwoHandleCharactersAndProvider() {
        assertThat(hasher.upiHint(" Fake.Payee.AR@OKAXIS ")).contains("\u2022\u2022\u2022\u2022ar@okaxis");
        assertThat(hasher.upiHint("abc@ybl")).as("short handle: nothing of it shown").contains("\u2022\u2022\u2022\u2022@ybl");
        assertThat(hasher.upiHint("not a upi")).isEmpty();
    }
}
