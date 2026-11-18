package com.offershield.common.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AES-256-GCM for letter text (PRD §10.1, §14.6): random 12-byte IV, key version, AAD bound to the submission. */
class TextCipherTest {

    private static final String LETTER = "Dear Candidate, pay the refundable deposit of Rs 5,000 today. ZEBRA-CIPHER-MARKER";

    private final String keyBase64 = randomKeyBase64(32);
    private final TextCipher cipher = new TextCipher(keyBase64);

    @Test
    void roundTrip() {
        byte[] aad = aad(UUID.randomUUID());

        SealedText sealed = cipher.encrypt(LETTER, aad);

        assertThat(sealed.keyVersion()).isEqualTo(TextCipher.CURRENT_KEY_VERSION);
        assertThat(sealed.bytes()).hasSize(TextCipher.IV_BYTES + LETTER.getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(new String(sealed.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("ZEBRA-CIPHER-MARKER");
        assertThat(cipher.decrypt(sealed, aad)).isEqualTo(LETTER);
    }

    @Test
    void tampered_fails() {
        byte[] aad = aad(UUID.randomUUID());
        SealedText sealed = cipher.encrypt(LETTER, aad);
        byte[] tampered = sealed.bytes().clone();
        tampered[TextCipher.IV_BYTES + 3] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(new SealedText(tampered, sealed.keyVersion()), aad))
                .isInstanceOf(TextCipher.DecryptionFailedException.class)
                .hasMessageNotContaining("ZEBRA");
    }

    @Test
    void wrongAad_fails() {
        SealedText sealed = cipher.encrypt(LETTER, aad(UUID.randomUUID()));

        assertThatThrownBy(() -> cipher.decrypt(sealed, aad(UUID.randomUUID())))
                .isInstanceOf(TextCipher.DecryptionFailedException.class);
    }

    @Test
    void wrongKey_fails() {
        byte[] aad = aad(UUID.randomUUID());
        SealedText sealed = cipher.encrypt(LETTER, aad);

        assertThatThrownBy(() -> new TextCipher(randomKeyBase64(32)).decrypt(sealed, aad))
                .isInstanceOf(TextCipher.DecryptionFailedException.class);
    }

    @Test
    void unknownKeyVersion_fails() {
        byte[] aad = aad(UUID.randomUUID());
        SealedText sealed = cipher.encrypt(LETTER, aad);

        assertThatThrownBy(() -> cipher.decrypt(new SealedText(sealed.bytes(), 99), aad))
                .isInstanceOf(TextCipher.DecryptionFailedException.class)
                .hasMessageContaining("key version");
    }

    @Test
    void ivUnique() {
        byte[] aad = aad(UUID.randomUUID());
        Set<String> ivs = new HashSet<>();
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            byte[] bytes = cipher.encrypt(LETTER, aad).bytes();
            ivs.add(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, TextCipher.IV_BYTES)));
            ciphertexts.add(Base64.getEncoder().encodeToString(bytes));
        }
        assertThat(ivs).hasSize(200);
        assertThat(ciphertexts).hasSize(200);
    }

    @Test
    void emptyText_roundTrips() {
        byte[] aad = aad(UUID.randomUUID());
        assertThat(cipher.decrypt(cipher.encrypt("", aad), aad)).isEmpty();
    }

    @Test
    void badKey_failsStartup() {
        String shortKey = randomKeyBase64(16);

        assertThatThrownBy(() -> new TextCipher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEXT_AES_KEY_BASE64")
                .hasMessageContaining("32 bytes")
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> new TextCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEXT_AES_KEY_BASE64 is not set");
        assertThatThrownBy(() -> new TextCipher(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEXT_AES_KEY_BASE64 is not set");
        assertThatThrownBy(() -> new TextCipher("not base64 at all!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64")
                .hasMessageNotContaining("not base64 at all");

        // The application context itself refuses to start.
        new ApplicationContextRunner()
                .withBean(TextCipher.class)
                .withPropertyValues("offershield.crypto.text-key-base64=" + shortKey)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("32 bytes").hasMessageNotContaining(shortKey));
        new ApplicationContextRunner()
                .withBean(TextCipher.class)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("TEXT_AES_KEY_BASE64 is not set"));
        new ApplicationContextRunner()
                .withBean(TextCipher.class)
                .withPropertyValues("offershield.crypto.text-key-base64=" + keyBase64)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TextCipher.class));
    }

    @Test
    void toString_neverShowsKey() {
        assertThat(cipher.toString()).doesNotContain(keyBase64);
    }

    private static byte[] aad(UUID submissionId) {
        return submissionId.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String randomKeyBase64(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
