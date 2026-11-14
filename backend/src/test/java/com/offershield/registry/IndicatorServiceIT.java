package com.offershield.registry;

import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.NotFoundException;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry's write API that P10 calls (PRD §6.4, §6.5; D-08, D-13, D-33.4, D-33.5; 02 §7 indicator machine):
 * report → UNVERIFIED with each person counted once; confirm → CONFIRMED (a report never downgrades it); an admin
 * reject removes that reporter and a marker left without reporters becomes REJECTED; Revert sends a CONFIRMED marker
 * back (REJECTED when nobody reported it), checks the version and writes the audit log. Random keys only.
 */
class IndicatorServiceIT extends IntegrationTest {

    @Autowired
    private IndicatorService indicators;
    @Autowired
    private ReportSnapshotService snapshots;
    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private JdbcTemplate jdbc;

    private RegistryTestData data;
    private UUID admin;

    @BeforeEach
    void setUp() {
        data = new RegistryTestData(provisioning, mockMvc, snapshots);
        admin = data.user();
    }

    @Test
    void reportScam_unverifiedEachPersonOnce() throws Exception {
        UUID reporter = data.user();
        String domain = RegistryTestData.domain();
        UUID submission = data.snapshot(reporter, domain, List.of(RegistryTestData.key()), List.of(),
                RegistryTestData.simhash());

        List<IndicatorView> first = indicators.reportScam(submission, reporter);
        List<IndicatorView> again = indicators.reportScam(submission, reporter);

        assertThat(first).extracting(IndicatorView::type)
                .containsExactly(IndicatorType.DOMAIN, IndicatorType.PHONE, IndicatorType.SIMHASH);
        assertThat(again).allSatisfy(marker -> {
            assertThat(marker.status()).isEqualTo(IndicatorStatus.UNVERIFIED);
            assertThat(marker.reporterCount()).isEqualTo(1);
        });
        assertThat(again.get(0).domain()).isEqualTo(domain);
        assertThat(again.get(1).domain()).as("phone markers are keys only").isNull();
        assertThat(indicators.markersOf(submission)).hasSize(3);
    }

    @Test
    void secondPersonSameMarker_countsTwo() throws Exception {
        String phone = RegistryTestData.key();
        UUID a = data.user();
        UUID b = data.user();
        indicators.reportScam(data.snapshot(a, null, List.of(phone), List.of(), null), a);

        List<IndicatorView> markers = indicators.reportScam(data.snapshot(b, null, List.of(phone), List.of(), null), b);

        assertThat(markers).singleElement().satisfies(marker -> assertThat(marker.reporterCount()).isEqualTo(2));
    }

    @Test
    void confirm_thenReport_staysConfirmed() throws Exception {
        UUID reporter = data.user();
        UUID submission = data.snapshot(reporter, null, List.of(RegistryTestData.key()), List.of(), null);

        IndicatorView confirmed = indicators.confirm(submission, admin).get(0);
        IndicatorView reported = indicators.reportScam(submission, reporter).get(0);

        assertThat(confirmed.status()).isEqualTo(IndicatorStatus.CONFIRMED);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(reported.status()).isEqualTo(IndicatorStatus.CONFIRMED);
        assertThat(reported.reporterCount()).isEqualTo(1);
    }

    @Test
    void sharedDomain_neverStored() throws Exception {
        UUID reporter = data.user();
        UUID submission = data.snapshot(reporter, "gmail.com", List.of(), List.of(), null);

        assertThat(indicators.confirm(submission, admin)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM indicator WHERE type = 'DOMAIN' AND match_key = "
                + "'gmail.com'", Integer.class)).isZero();
    }

    @Test
    void withdrawReport_onlyThatReporterRemoved() throws Exception {
        String phone = RegistryTestData.key();
        String upi = RegistryTestData.key();
        UUID a = data.user();
        UUID b = data.user();
        UUID first = data.snapshot(a, null, List.of(phone), List.of(upi), null);
        UUID second = data.snapshot(b, null, List.of(phone), List.of(), null);
        indicators.reportScam(first, a);
        indicators.reportScam(second, b);

        List<IndicatorView> after = indicators.withdrawReport(first, a);

        assertThat(after).hasSize(2);
        assertThat(after).filteredOn(marker -> marker.type() == IndicatorType.PHONE).singleElement()
                .satisfies(marker -> {
                    assertThat(marker.status()).as("B still reports it").isEqualTo(IndicatorStatus.UNVERIFIED);
                    assertThat(marker.reporterCount()).isEqualTo(1);
                });
        assertThat(after).filteredOn(marker -> marker.type() == IndicatorType.UPI).singleElement()
                .satisfies(marker -> {
                    assertThat(marker.status()).isEqualTo(IndicatorStatus.REJECTED);
                    assertThat(marker.reporterCount()).isZero();
                });
        assertThat(indicators.withdrawReport(first, a)).as("nothing left to remove").isEmpty();
    }

    @Test
    void rejectedMarker_newReporter_unverifiedAgain() throws Exception {
        String phone = RegistryTestData.key();
        UUID a = data.user();
        UUID b = data.user();
        UUID first = data.snapshot(a, null, List.of(phone), List.of(), null);
        indicators.reportScam(first, a);
        indicators.withdrawReport(first, a);

        IndicatorView marker = indicators.reportScam(data.snapshot(b, null, List.of(phone), List.of(), null), b).get(0);

        assertThat(marker.status()).isEqualTo(IndicatorStatus.UNVERIFIED);
        assertThat(marker.reporterCount()).isEqualTo(1);
    }

    @Test
    void withdrawReport_confirmedStaysConfirmed() throws Exception {
        UUID reporter = data.user();
        UUID submission = data.snapshot(reporter, null, List.of(RegistryTestData.key()), List.of(), null);
        indicators.reportScam(submission, reporter);
        indicators.confirm(submission, admin);

        assertThat(indicators.withdrawReport(submission, reporter)).singleElement()
                .satisfies(marker -> assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED));
    }

    @Test
    void revert_confirmedToUnverified_audited() throws Exception {
        UUID reporter = data.user();
        UUID submission = data.snapshot(reporter, null, List.of(RegistryTestData.key()), List.of(), null);
        indicators.reportScam(submission, reporter);
        IndicatorView confirmed = indicators.confirm(submission, admin).get(0);

        IndicatorView reverted = indicators.revert(confirmed.id(), confirmed.version(), admin);

        assertThat(reverted.status()).isEqualTo(IndicatorStatus.UNVERIFIED);
        assertThat(reverted.confirmedAt()).isNull();
        assertThat(reverted.version()).isEqualTo(confirmed.version() + 1);
        assertThat(indicators.find(confirmed.id())).contains(reverted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'INDICATOR_REVERTED' "
                + "AND target_id = ? AND actor_id = ?", Integer.class, com.offershield.common.Ids.toBytes(confirmed.id()),
                com.offershield.common.Ids.toBytes(admin))).isEqualTo(1);
    }

    @Test
    void revert_nobodyReported_rejected() throws Exception {
        UUID submission = data.snapshot(data.user(), null, List.of(RegistryTestData.key()), List.of(), null);
        IndicatorView confirmed = indicators.confirm(submission, admin).get(0);

        assertThat(indicators.revert(confirmed.id(), confirmed.version(), admin).status())
                .isEqualTo(IndicatorStatus.REJECTED);
    }

    @Test
    void revert_staleOrNotConfirmedOrUnknown_refused() throws Exception {
        UUID reporter = data.user();
        UUID submission = data.snapshot(reporter, null, List.of(RegistryTestData.key()), List.of(), null);
        IndicatorView unverified = indicators.reportScam(submission, reporter).get(0);

        assertThatThrownBy(() -> indicators.revert(unverified.id(), unverified.version(), admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Only a confirmed");
        IndicatorView confirmed = indicators.confirm(submission, admin).get(0);
        assertThatThrownBy(() -> indicators.revert(confirmed.id(), confirmed.version() - 1, admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("changed by someone else");
        assertThatThrownBy(() -> indicators.revert(UUID.randomUUID(), 0, admin))
                .isInstanceOf(NotFoundException.class);
        assertThat(indicators.find(confirmed.id()).orElseThrow().status()).isEqualTo(IndicatorStatus.CONFIRMED);
    }

    @Test
    void noSnapshot_404() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> indicators.reportScam(unknown, admin)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> indicators.confirm(unknown, admin)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> indicators.markersOf(unknown)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void markersOf_phoneAndUpiShowSourceAndMaskedHint_neverKeys() throws Exception {
        // P13 (N2): a reviewer sees where each phone / UPI marker came from and a masked hint, never the HMAC key.
        UUID owner = data.user();
        String domain = RegistryTestData.domain();
        String phone = RegistryTestData.key();
        String upi = RegistryTestData.key();
        UUID submission = data.submission(owner);
        snapshots.store(UUID.randomUUID(), new AnalysisCompletedEvent.Payload(submission, owner, null,
                AnalysisCompletedEvent.READY, 30, "HIGH", true, List.of(), List.of(), domain, List.of(phone),
                List.of(upi), null, true, "TYPED", 1, List.of(
                new AnalysisCompletedEvent.MarkerHint("PHONE", phone, "TYPED", "\u2022\u2022\u2022\u20221234"),
                new AnalysisCompletedEvent.MarkerHint("UPI", upi, "LETTER", "\u2022\u2022\u2022\u2022ar@okaxis"))),
                Instant.now());
        indicators.reportScam(submission, owner);

        List<IndicatorView> markers = indicators.markersOf(snapshots.find(submission).orElseThrow());

        assertThat(markers).extracting(IndicatorView::type)
                .containsExactly(IndicatorType.DOMAIN, IndicatorType.PHONE, IndicatorType.UPI);
        assertThat(markers.get(0).source()).isNull();
        assertThat(markers.get(0).hint()).isNull();
        assertThat(markers.get(0).domain()).isEqualTo(domain);
        assertThat(markers.get(1).source()).isEqualTo("TYPED");
        assertThat(markers.get(1).hint()).isEqualTo("\u2022\u2022\u2022\u20221234");
        assertThat(markers.get(2).source()).isEqualTo("LETTER");
        assertThat(markers.get(2).hint()).isEqualTo("\u2022\u2022\u2022\u2022ar@okaxis");
        assertThat(markers.toString()).doesNotContain(phone).doesNotContain(upi);
    }

    @Test
    void parallelReportsAndConfirm_sameMarkers_noLostReporterNoDeadlock() throws Exception {
        // P13 (test gap): N people report letters sharing a domain and a phone number while a reviewer confirms one
        // of them; the rows are locked in a fixed order, so nobody is lost and nothing deadlocks.
        int people = 6;
        String domain = RegistryTestData.domain();
        String phone = RegistryTestData.key();
        List<UUID> reporters = new java.util.ArrayList<>();
        List<UUID> submissions = new java.util.ArrayList<>();
        for (int i = 0; i < people; i++) {
            UUID reporter = data.user();
            reporters.add(reporter);
            submissions.add(data.snapshot(reporter, domain, List.of(phone), List.of(), null));
        }
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> results = new java.util.ArrayList<>();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(people + 1)) {
            for (int i = 0; i < people; i++) {
                UUID reporter = reporters.get(i);
                UUID submission = submissions.get(i);
                results.add(pool.submit(() -> {
                    start.await();
                    return indicators.reportScam(submission, reporter);
                }));
            }
            results.add(pool.submit(() -> {
                start.await();
                return indicators.confirm(submissions.get(0), admin);
            }));
            start.countDown();
            for (java.util.concurrent.Future<?> result : results) {
                result.get(60, java.util.concurrent.TimeUnit.SECONDS); // rethrows a deadlock or lock timeout
            }
        }

        List<IndicatorView> markers = indicators.markersOf(submissions.get(0));
        assertThat(markers).extracting(IndicatorView::type).containsExactly(IndicatorType.DOMAIN, IndicatorType.PHONE);
        assertThat(markers).allSatisfy(marker -> {
            assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED);
            assertThat(marker.reporterCount()).isEqualTo(people);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM indicator WHERE type = 'DOMAIN' AND match_key = ?",
                Integer.class, domain)).isEqualTo(1);
    }
}
