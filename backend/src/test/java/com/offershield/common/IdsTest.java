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
}
