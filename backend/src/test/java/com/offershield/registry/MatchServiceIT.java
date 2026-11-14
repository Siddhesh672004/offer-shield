package com.offershield.registry;

import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.company.CreateKnownCompanyRequest;
import com.offershield.company.KnownCompanyResponse;
import com.offershield.company.KnownCompanyService;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9-03 against MySQL 8.4 (PRD §7.1 C10–C12, §10.1; D-08, D-33.5): exact matches on domain, phone and UPI keys;
 * SimHash matches by {@code BIT_COUNT(a ^ b) <= 3} on all 64 bits (fingerprints with the top bit set included); the
 * unverified count is distinct people across every matching marker; REJECTED markers and shared domains never match.
 * Markers are made through the registry's own write API from stored snapshots; all keys are random.
 */
class MatchServiceIT extends IntegrationTest {

    @Autowired
    private MatchService matches;
    @Autowired
    private IndicatorService indicators;
    @Autowired
    private ReportSnapshotService snapshots;
    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private KnownCompanyService knownCompanies;

    private RegistryTestData data;
    private UUID reviewer;

    @BeforeEach
    void setUp() {
        data = new RegistryTestData(provisioning, mockMvc, snapshots);
        reviewer = data.user();
    }

    @Test
    void confirmedPhone_matches() throws Exception {
        String phone = RegistryTestData.key();
        UUID submission = data.snapshot(data.user(), null, List.of(phone), List.of(), null);
        indicators.confirm(submission, reviewer);

        RegistryMatch match = matches.match(new MarkerQuery(null, List.of(RegistryTestData.key(), phone), List.of(),
                null));

        assertThat(match.confirmedExact()).singleElement().satisfies(marker -> {
            assertThat(marker.type()).isEqualTo(IndicatorType.PHONE);
            assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED);
            assertThat(marker.key()).isEqualTo(phone);
        });
        assertThat(match.unverifiedReporters()).isZero();
        assertThat(matches.match(new MarkerQuery(null, List.of(RegistryTestData.key()), List.of(), null)).markers())
                .isEmpty();
    }

    @Test
    void confirmedDomainAndUpi_match() throws Exception {
        String domain = RegistryTestData.domain();
        String upi = RegistryTestData.key();
        UUID submission = data.snapshot(data.user(), "hr." + domain, List.of(), List.of(upi), null);
        indicators.confirm(submission, reviewer);

        RegistryMatch match = matches.match(new MarkerQuery(domain, List.of(), List.of(upi), null));

        assertThat(match.confirmedExact()).extracting(MatchedMarker::type)
                .containsExactlyInAnyOrder(IndicatorType.DOMAIN, IndicatorType.UPI);
    }

    @Test
    void simhashWithin3() throws Exception {
        long fingerprint = RegistryTestData.simhash();
        UUID submission = data.snapshot(data.user(), null, List.of(), List.of(), fingerprint);
        indicators.confirm(submission, reviewer);

        RegistryMatch three = matches.match(new MarkerQuery(null, List.of(), List.of(), fingerprint ^ 0b1011L));
        RegistryMatch four = matches.match(new MarkerQuery(null, List.of(), List.of(), fingerprint ^ 0b1111L));
        RegistryMatch topBits = matches.match(new MarkerQuery(null, List.of(), List.of(), fingerprint ^ (0b11L << 62)));

        assertThat(three.closestConfirmedWording()).hasValueSatisfying(marker -> {
            assertThat(marker.distance()).isEqualTo(3);
            assertThat(marker.key()).isEqualTo(String.format("%016x", fingerprint));
        });
        assertThat(four.markers()).as("4 bits differ").isEmpty();
        assertThat(topBits.closestConfirmedWording()).hasValueSatisfying(marker ->
                assertThat(marker.distance()).isEqualTo(2));
        assertThat(matches.match(new MarkerQuery(null, List.of(), List.of(), fingerprint)).closestConfirmedWording())
                .hasValueSatisfying(marker -> assertThat(marker.distance()).isZero());
    }

    @Test
    void distinctReporters_union() throws Exception {
        String domain = RegistryTestData.domain();
        String phoneA = RegistryTestData.key();
        String phoneB = RegistryTestData.key();
        UUID userA = data.user();
        UUID userB = data.user();
        // A reports a letter with the domain and phone A; B one with the domain and phone B; A also one with phone B.
        UUID first = data.snapshot(userA, domain, List.of(phoneA), List.of(), null);
        UUID second = data.snapshot(userB, domain, List.of(phoneB), List.of(), null);
        UUID third = data.snapshot(userA, null, List.of(phoneB), List.of(), null);
        indicators.reportScam(first, userA);
        indicators.reportScam(second, userB);
        indicators.reportScam(third, userA);

        RegistryMatch all = matches.match(new MarkerQuery(domain, List.of(phoneA, phoneB), List.of(), null));
        RegistryMatch onlyPhoneA = matches.match(new MarkerQuery(null, List.of(phoneA), List.of(), null));

        assertThat(all.unverified()).hasSize(3);
        assertThat(all.confirmedExact()).as("unverified markers never count as confirmed").isEmpty();
        assertThat(all.unverifiedReporters()).as("A and B, each once").isEqualTo(2);
        assertThat(onlyPhoneA.unverifiedReporters()).isEqualTo(1);
    }

    @Test
    void exactUnverifiedReporters_excludeWordingOnlyReporters() throws Exception {
        // P13 (C12 fairness): the count used when wording-only reports must not count (copied-letter guard).
        String phone = RegistryTestData.key();
        long fingerprint = RegistryTestData.simhash();
        UUID phoneReporter = data.user();
        UUID wordingReporter = data.user();
        indicators.reportScam(data.snapshot(phoneReporter, null, List.of(phone), List.of(), null), phoneReporter);
        indicators.reportScam(data.snapshot(wordingReporter, null, List.of(), List.of(), fingerprint), wordingReporter);

        RegistryMatch match = matches.match(new MarkerQuery(null, List.of(phone), List.of(), fingerprint));

        assertThat(match.unverifiedReporters()).isEqualTo(2);
        assertThat(match.exactUnverifiedReporters()).isEqualTo(1);
        RegistryMatch discounted = match.withoutUnverifiedWording();
        assertThat(discounted.unverified()).singleElement()
                .satisfies(marker -> assertThat(marker.type()).isEqualTo(IndicatorType.PHONE));
        assertThat(discounted.unverifiedReporters()).isEqualTo(1);
    }

    @Test
    void rejected_ignored() throws Exception {
        String phone = RegistryTestData.key();
        long fingerprint = RegistryTestData.simhash();
        UUID reporter = data.user();
        UUID submission = data.snapshot(reporter, null, List.of(phone), List.of(), fingerprint);
        indicators.reportScam(submission, reporter);
        assertThat(matches.match(new MarkerQuery(null, List.of(phone), List.of(), fingerprint)).markers()).hasSize(2);

        List<IndicatorView> withdrawn = indicators.withdrawReport(submission, reporter);

        assertThat(withdrawn).extracting(IndicatorView::status).containsOnly(IndicatorStatus.REJECTED);
        RegistryMatch after = matches.match(new MarkerQuery(null, List.of(phone), List.of(), fingerprint));
        assertThat(after.markers()).isEmpty();
        assertThat(after.unverifiedReporters()).isZero();
    }

    @Test
    void sharedDomains_neverMatch() throws Exception {
        // D-08: a free-mail domain never becomes a marker; a domain that is official for a known company is ignored
        // when matching even if it was stored as a marker before the company was listed.
        String later = RegistryTestData.domain();
        UUID reporter = data.user();
        UUID gmail = data.snapshot(reporter, "gmail.com", List.of(RegistryTestData.key()), List.of(), null);
        UUID beforeListing = data.snapshot(reporter, later, List.of(), List.of(), null);
        indicators.confirm(gmail, reviewer);
        indicators.confirm(beforeListing, reviewer);
        assertThat(matches.match(new MarkerQuery("gmail.com", List.of(), List.of(), null)).markers()).isEmpty();
        assertThat(matches.match(new MarkerQuery(later, List.of(), List.of(), null)).confirmedExact()).hasSize(1);

        KnownCompanyResponse company = knownCompanies.create(new CreateKnownCompanyRequest("Registry Test Company "
                + later.substring(8, 16).replaceAll("[0-9]", "x"), List.of(later)), reviewer);
        try {
            assertThat(matches.match(new MarkerQuery(later, List.of(), List.of(), null)).markers()).isEmpty();
        } finally {
            knownCompanies.delete(company.id(), reviewer);
        }
    }
}
