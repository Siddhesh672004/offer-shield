package com.offershield.registry;

import com.offershield.analysis.checks.KnownCompanyFixtures;
import com.offershield.common.FreeMailDomains;
import com.offershield.company.KnownCompanies;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9-03, D-08 (review focus #2): the markers a reported or confirmed scam adds to the registry. A Gmail-sent scam must
 * never make {@code gmail.com} itself a scam marker, and neither may a known company's official domain; the same rule
 * applies to everyone. Phone numbers and UPI IDs arrive only as HMAC keys. Fake values only.
 */
class IndicatorFactoryTest {

    private static final String PHONE = "a".repeat(64);
    private static final String UPI = "b".repeat(64);
    private static final long SIMHASH = 0x8000_0000_0000_00FFL;

    private final KnownCompanies known = KnownCompanyFixtures.knownCompanies(
            KnownCompanyFixtures.company("Infosys", "infosys.com"));
    private final IndicatorFactory factory = new IndicatorFactory(new SharedDomains(new FreeMailDomains(), known));

    @Test
    void freeMailDomain_neverBecomesIndicator() {
        List<MarkerCandidate> markers = factory.candidates(snapshot("gmail.com"));

        assertThat(markers).noneMatch(marker -> marker.type() == IndicatorType.DOMAIN);
        assertThat(markers).extracting(MarkerCandidate::type)
                .containsExactly(IndicatorType.PHONE, IndicatorType.UPI, IndicatorType.SIMHASH);
    }

    @Test
    void officialDomain_neverBecomesIndicator() {
        assertThat(factory.candidates(snapshot("infosys.com")))
                .noneMatch(marker -> marker.type() == IndicatorType.DOMAIN);
    }

    @Test
    void otherDomain_becomesIndicatorWithEveryOtherMarker() {
        List<MarkerCandidate> markers = factory.candidates(snapshot("inf0sys-careers.com"));

        assertThat(markers).containsExactly(
                new MarkerCandidate(IndicatorType.DOMAIN, "inf0sys-careers.com", null),
                new MarkerCandidate(IndicatorType.PHONE, PHONE, null),
                new MarkerCandidate(IndicatorType.UPI, UPI, null),
                new MarkerCandidate(IndicatorType.SIMHASH, "80000000000000ff", SIMHASH));
    }

    @Test
    void noDomainNoFingerprint_onlyContactMarkers() {
        ReportSnapshotView snapshot = new ReportSnapshotView(UUID.randomUUID(), UUID.randomUUID(), null, "HIGH", 0,
                true, false, null, List.of(PHONE), List.of(), null, List.of(), true, Instant.now());

        assertThat(factory.candidates(snapshot)).containsExactly(new MarkerCandidate(IndicatorType.PHONE, PHONE, null));
    }

    @Test
    void malformedKeys_skipped() {
        ReportSnapshotView snapshot = new ReportSnapshotView(UUID.randomUUID(), UUID.randomUUID(), null, "HIGH", 0,
                true, false, null, List.of("+919999901234", PHONE.toUpperCase()), List.of("test.p9@upi"), null,
                List.of(), true, Instant.now());

        assertThat(factory.candidates(snapshot)).isEmpty();
    }

    private static ReportSnapshotView snapshot(String domain) {
        return new ReportSnapshotView(UUID.randomUUID(), UUID.randomUUID(), null, "HIGH", 25, true, false, domain,
                List.of(PHONE), List.of(UPI), SIMHASH, List.of(), true, Instant.now());
    }

    // --- P13 (security review S2): a sender domain taken from the letter itself ---

    @Test
    void letterSourcedDomain_manyEmailDomainsInLetter_noDomainMarker() {
        // The author could have planted an innocent company's address first; with other domains around, D-33.6's pick
        // is not reliable enough to brand a domain a scam. Phone, UPI and wording markers still count.
        List<MarkerCandidate> markers = factory.candidates(sourced("inf0sys-careers.com", "LETTER", 2));

        assertThat(markers).extracting(MarkerCandidate::type)
                .containsExactly(IndicatorType.PHONE, IndicatorType.UPI, IndicatorType.SIMHASH);
    }

    @Test
    void letterSourcedDomain_onlyEmailDomainInLetter_domainMarker() {
        assertThat(factory.candidates(sourced("inf0sys-careers.com", "LETTER", 1)))
                .contains(new MarkerCandidate(IndicatorType.DOMAIN, "inf0sys-careers.com", null));
    }

    @Test
    void typedDomain_manyEmailDomainsInLetter_domainMarker() {
        assertThat(factory.candidates(sourced("inf0sys-careers.com", "TYPED", 3)))
                .contains(new MarkerCandidate(IndicatorType.DOMAIN, "inf0sys-careers.com", null));
    }

    private static ReportSnapshotView sourced(String domain, String senderSource, int letterEmailDomains) {
        return new ReportSnapshotView(UUID.randomUUID(), UUID.randomUUID(), null, "HIGH", 25, true, false, domain,
                List.of(PHONE), List.of(UPI), SIMHASH, List.of(), true, Instant.now(), senderSource, letterEmailDomains,
                List.of());
    }
}
