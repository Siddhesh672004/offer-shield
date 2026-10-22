package com.offershield.common.crypto;

/**
 * AES-GCM output as stored: {@code bytes} = 12-byte IV followed by the ciphertext and 16-byte tag, plus the version
 * of the key that sealed it (for rotation).
 */
public record SealedText(byte[] bytes, int keyVersion) {

    public SealedText {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes are required");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "SealedText[length=" + bytes.length + ", keyVersion=" + keyVersion + "]";
    }
}
