package com.offershield.common;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/** Primary-key generator: UUID version 7 (time-ordered), so inserts into InnoDB stay roughly sequential. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextLong();
        long randB = RANDOM.nextLong();

        // High 64 bits: 48-bit Unix ms | version 7 | 12 random bits.
        long mostSignificant = (millis << 16) | 0x7000L | (randA & 0x0FFFL);
        // Low 64 bits: IETF variant (10) | 62 random bits.
        long leastSignificant = (randB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }

    /** The {@code BINARY(16)} form of an ID for plain SQL (the same byte order Hibernate uses). */
    public static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** An ID read from a {@code BINARY(16)} column; {@code null} stays {@code null}. */
    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length != 16) {
            throw new IllegalArgumentException("an ID has 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
