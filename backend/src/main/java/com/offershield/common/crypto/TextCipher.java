package com.offershield.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM for letter text at rest (PRD §10.1, §14.6). Each call uses a fresh random 12-byte IV, and the caller's
 * AAD (the submission ID) is authenticated, so a ciphertext copied onto another row fails to decrypt. The 32-byte key
 * comes from {@code TEXT_AES_KEY_BASE64}; a missing or malformed key stops the application at startup. Neither the
 * key nor any plaintext ever appears in an exception message.
 */
@Component
public class TextCipher {

    /** Version written next to every ciphertext; a rotated key would become version 2. */
    public static final int CURRENT_KEY_VERSION = 1;
    public static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TextCipher(@Value("${offershield.crypto.text-key-base64:}") String keyBase64) {
        this.key = new SecretKeySpec(decodeKey(keyBase64), "AES");
    }

    public SealedText encrypt(String plaintext, byte[] aad) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = Arrays.copyOf(iv, IV_BYTES + ciphertext.length);
            System.arraycopy(ciphertext, 0, sealed, IV_BYTES, ciphertext.length);
            return new SealedText(sealed, CURRENT_KEY_VERSION);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM encryption failed: " + ex.getClass().getSimpleName());
        }
    }

    /** @throws DecryptionFailedException for a wrong key version, wrong AAD, wrong key or tampered bytes */
    public String decrypt(SealedText sealed, byte[] aad) {
        if (sealed.keyVersion() != CURRENT_KEY_VERSION) {
            throw new DecryptionFailedException("Unknown key version " + sealed.keyVersion());
        }
        byte[] bytes = sealed.bytes();
        if (bytes.length < IV_BYTES + TAG_BITS / 8) {
            throw new DecryptionFailedException("Ciphertext too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            cipher.updateAAD(aad);
            byte[] plaintext = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new DecryptionFailedException("Authentication failed: " + ex.getClass().getSimpleName());
        }
    }

    @Override
    public String toString() {
        return "TextCipher[AES-256-GCM, keyVersion=" + CURRENT_KEY_VERSION + "]";
    }

    private static byte[] decodeKey(String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalStateException(
                    "TEXT_AES_KEY_BASE64 is not set. Generate one with 'openssl rand -base64 32' and put it in .env.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("TEXT_AES_KEY_BASE64 is not valid Base64.");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException("TEXT_AES_KEY_BASE64 must decode to 32 bytes (AES-256), but it decodes to "
                    + key.length + " bytes.");
        }
        return key;
    }

    /** The ciphertext could not be authenticated. The message never contains key material or plaintext. */
    public static class DecryptionFailedException extends RuntimeException {

        public DecryptionFailedException(String message) {
            super(message);
        }
    }
}
