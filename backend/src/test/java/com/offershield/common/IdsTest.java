package com.offershield.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void newId_generated_isVersion7WithIetfVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void newId_generatedLater_sortsAfterEarlierId() throws InterruptedException {
        UUID earlier = Ids.newId();
        Thread.sleep(2);
        UUID later = Ids.newId();

        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void newId_manyCalls_allDistinct() {
        long distinct = Stream.generate(Ids::newId).limit(1000).distinct().count();

        assertThat(distinct).isEqualTo(1000);
    }

    @Test
    void newId_generated_embedsCurrentUnixMillisInTopBits() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();

        long embeddedMillis = id.getMostSignificantBits() >>> 16;

        assertThat(embeddedMillis).isBetween(before, after);
    }

    @Test
    void toBytesFromBytes_roundTripBigEndian() {
        UUID id = UUID.fromString("0190c9a0-0000-7000-8000-0000000000a1");

        byte[] bytes = Ids.toBytes(id);

        assertThat(bytes).hasSize(16);
        assertThat(bytes[0]).isEqualTo((byte) 0x01);
        assertThat(bytes[15]).isEqualTo((byte) 0xa1);
        assertThat(Ids.fromBytes(bytes)).isEqualTo(id);
        assertThat(Ids.fromBytes(null)).isNull();
    }
}
