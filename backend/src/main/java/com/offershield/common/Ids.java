package com.offershield.common;

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
}
