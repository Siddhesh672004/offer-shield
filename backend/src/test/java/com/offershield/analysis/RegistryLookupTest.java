package com.offershield.analysis;

import com.offershield.analysis.checks.AiFindings;
import com.offershield.analysis.checks.CopiedLetterGuard;
import com.offershield.analysis.checks.KnownCompanyFixtures;
import com.offershield.analysis.checks.Letter;
import com.offershield.analysis.checks.Letters;
import com.offershield.analysis.checks.RegistryFindings;
import com.offershield.common.FreeMailDomains;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.registry.IndicatorHasher;
import com.offershield.registry.IndicatorStatus;
import com.offershield.registry.IndicatorType;
import com.offershield.registry.MarkerQuery;
import com.offershield.registry.MatchService;
import com.offershield.registry.MatchedMarker;
import com.offershield.registry.RegistryMatch;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P9 (R-F, PRD §10.1, D-27): the analysis' registry step. Phone numbers and UPI IDs from the letter, the typed fields
 * and the AI become HMAC keys before anything else sees them; the registry gets only keys; a registry failure makes
 * C10–C12 unavailable instead of failing the report. Fake numbers only.
 */
class RegistryLookupTest {

    private static final String LETTER = "Dear Candidate, to confirm your joining call +91 99999 01234 or pay through "
            + "test.p9@upi. Write to hr@scam-firm-p9.com for anything else.";

    private final IndicatorHasher hasher = new IndicatorHasher("registry-lookup-test-pepper-" + "y".repeat(20));
    private final MatchService matches = mock(MatchService.class);
    private final RegistryLookup lookup = new RegistryLookup(hasher, matches, guard());

    @Test
    void markers_fromTextTypedAndAi_hashedNeverRaw() {
        Letter letter = Letters.from("hr@mail.scam-firm-p9.com", LETTER)
                .withAi(new AiFindings(List.of(), null, List.of("+91 99999 01234", "99999 01777"), List.of()));
        Letter typed = new Letter(letter.text(), letter.senderEmail(), letter.senderDomain(), letter.domain(),
                "09999901555", "Typed.P9@UPI", letter.ai(), letter.registry());

        LetterMarkers markers = lookup.markers(typed);

        assertThat(markers.domain()).isEqualTo("scam-firm-p9.com");
        assertThat(markers.phoneHmacs()).containsExactly(hasher.phone("+919999901234").orElseThrow(),
                hasher.phone("+919999901555").orElseThrow(), hasher.phone("+919999901777").orElseThrow());
        assertThat(markers.upiHmacs()).containsExactly(hasher.upi("test.p9@upi").orElseThrow(),
                hasher.upi("typed.p9@upi").orElseThrow());
        assertThat(markers.aiOnlyKeys()).containsExactly(hasher.phone("+919999901777").orElseThrow());
        assertThat(markers.simhash()).as("under 50 words").isNull();
        assertThat(markers.complete()).isTrue();
        assertThat(markers.toString()).doesNotContain("99999").doesNotContain("p9@upi");
    }

    @Test
    void moreThanTenPhones_cappedIncomplete() {
        String many = IntStream.range(0, 12).mapToObj(i -> String.format("+91 99999 0%04d", 2000 + i))
                .collect(Collectors.joining(", "));

        LetterMarkers markers = lookup.markers(Letters.text("Call any of " + many));

        assertThat(markers.phoneHmacs()).hasSize(RegistryLookup.MAX_KEYS);
        assertThat(markers.complete()).isFalse();
    }

    @Test
    void longLetter_hasFingerprint() {
        assertThat(lookup.markers(Letters.text(Letters.fixture(Letters.GENUINE))).simhash()).isNotNull();
    }

    @Test
    void lookup_registryGetsOnlyKeys() {
        when(matches.match(any())).thenReturn(new RegistryMatch(List.of(new MatchedMarker(UUID.randomUUID(),
                IndicatorType.PHONE, IndicatorStatus.CONFIRMED, hasher.phone("+919999901234").orElseThrow(), null)), 0));

        RegistryLookup.Result result = lookup.lookup(Letters.text(LETTER));

        ArgumentCaptor<MarkerQuery> query = ArgumentCaptor.forClass(MarkerQuery.class);
        verify(matches).match(query.capture());
        assertThat(query.getValue().phoneHmacs()).allMatch(key -> key.matches("[0-9a-f]{64}"));
        assertThat(query.getValue().upiHmacs()).allMatch(key -> key.matches("[0-9a-f]{64}"));
        assertThat(result.findings().available()).isTrue();
        assertThat(result.findings().exactKeys()).isTrue();
        assertThat(result.findings().match().confirmedExact()).hasSize(1);
    }

    @Test
    void registryDown_unavailableNoValuesLogged() {
        when(matches.match(any())).thenThrow(new IllegalStateException("db down +91 99999 01234"));

        try (LogCapture logs = LogCapture.root()) {
            RegistryLookup.Result result = lookup.lookup(Letters.text(LETTER));

            assertThat(result.findings().available()).isFalse();
            assertThat(result.markers().phoneHmacs()).hasSize(1);
            assertThat(logs.events()).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .isEqualTo("registry match failed error=IllegalStateException"));
            assertThat(logs.events()).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("99999"));
        }
    }

    @Test
    void nothingToCompare_registryNotAsked() {
        RegistryLookup.Result result = lookup.lookup(Letters.text("Dear Candidate, welcome aboard."));

        assertThat(result.findings()).isEqualTo(RegistryFindings.NOTHING_TO_COMPARE);
        verifyNoInteractions(matches);
    }

    // --- P13 ---

    @Test
    void keysFail_keepsValidatedDomain_incomplete() {
        // Silent failure 6: a failure while hashing must not drop the sender domain the SSRF guard already passed.
        IndicatorHasher failing = mock(IndicatorHasher.class);
        when(failing.phone(any())).thenThrow(new IllegalStateException("hash failed +91 99999 01234"));
        RegistryLookup broken = new RegistryLookup(failing, matches, guard());

        try (LogCapture logs = LogCapture.of(RegistryLookup.class)) {
            RegistryLookup.Result result = broken.lookup(Letters.from("hr@mail.scam-firm-p9.com", LETTER));

            assertThat(result.markers().domain()).isEqualTo("scam-firm-p9.com");
            assertThat(result.markers().complete()).isFalse();
            assertThat(result.markers().phoneHmacs()).isEmpty();
            assertThat(result.findings().available()).isFalse();
            assertThat(result.findings().exactKeys()).isTrue();
            assertThat(logs.events()).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .isEqualTo("registry keys failed error=IllegalStateException"));
            assertThat(logs.events()).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("99999"));
        }
    }

    @Test
    void wordingOnlyReports_officialSenderOfNamedCompany_notCounted() {
        // P13 (test gap, C12 fairness): the copied-letter guard (PRD §7.3) applies to unverified wording reports too.
        when(matches.match(any())).thenReturn(new RegistryMatch(List.of(wording(IndicatorStatus.UNVERIFIED)), 4, 0));

        RegistryLookup.Result official = lookup.lookup(Letters.from("talent@northwind-analytics.com", NORTHWIND));
        RegistryLookup.Result other = lookup.lookup(Letters.from("talent@northwind-hiring.com", NORTHWIND));

        assertThat(official.findings().match().unverified()).isEmpty();
        assertThat(official.findings().match().unverifiedReporters()).isZero();
        assertThat(other.findings().match().unverified()).hasSize(1);
        assertThat(other.findings().match().unverifiedReporters()).isEqualTo(4);
    }

    @Test
    void wordingAndPhoneReports_officialSender_onlyPhoneReportersCounted() {
        MatchedMarker phone = new MatchedMarker(UUID.randomUUID(), IndicatorType.PHONE, IndicatorStatus.UNVERIFIED,
                hasher.phone("+919999901234").orElseThrow(), null);
        MatchedMarker confirmedWording = new MatchedMarker(UUID.randomUUID(), IndicatorType.SIMHASH,
                IndicatorStatus.CONFIRMED, "00000000000000aa", 1);
        when(matches.match(any())).thenReturn(new RegistryMatch(List.of(wording(IndicatorStatus.UNVERIFIED), phone,
                confirmedWording), 5, 2));

        RegistryLookup.Result result = lookup.lookup(Letters.from("talent@northwind-analytics.com", NORTHWIND));

        assertThat(result.findings().match().unverified()).containsExactly(phone);
        assertThat(result.findings().match().unverifiedReporters()).isEqualTo(2);
        assertThat(result.findings().match().closestConfirmedWording()).as("C11 keeps its own guard")
                .contains(confirmedWording);
    }

    @Test
    void markers_hintsMaskedWithSource() {
        // N2: reviewers see where each phone / UPI key came from and a masked hint, never the value.
        Letter letter = Letters.text(LETTER)
                .withAi(new AiFindings(List.of(), null, List.of("99999 01777"), List.of()));
        Letter typed = new Letter(letter.text(), null, null, null, "09999901555", "Typed.Ar@OKAXIS", letter.ai(),
                letter.registry());

        LetterMarkers markers = lookup.markers(typed);

        assertThat(markers.hints()).containsExactlyInAnyOrder(
                new AnalysisCompletedEvent.MarkerHint("PHONE", hasher.phone("+919999901234").orElseThrow(), "LETTER",
                        "••••1234"),
                new AnalysisCompletedEvent.MarkerHint("PHONE", hasher.phone("+919999901555").orElseThrow(), "TYPED",
                        "••••1555"),
                new AnalysisCompletedEvent.MarkerHint("PHONE", hasher.phone("+919999901777").orElseThrow(), "LETTER",
                        "••••1777"),
                new AnalysisCompletedEvent.MarkerHint("UPI", hasher.upi("test.p9@upi").orElseThrow(), "LETTER",
                        "••••p9@upi"),
                new AnalysisCompletedEvent.MarkerHint("UPI", hasher.upi("typed.ar@okaxis").orElseThrow(), "TYPED",
                        "••••ar@okaxis"));
        assertThat(markers.hints().toString()).doesNotContain("99999").doesNotContain("test.p9")
                .doesNotContain("typed.ar");
    }

    @Test
    void markers_numberTypedAndInLetter_letterSource() {
        Letter typed = new Letter(LETTER, null, null, null, "+91 99999 01234", null);

        assertThat(lookup.markers(typed).hints()).filteredOn(hint -> hint.type().equals("PHONE"))
                .singleElement().satisfies(hint -> assertThat(hint.source()).isEqualTo("LETTER"));
    }

    private static final String NORTHWIND = "Northwind Analytics is pleased to offer you the role of Analyst.";

    private static MatchedMarker wording(IndicatorStatus status) {
        return new MatchedMarker(UUID.randomUUID(), IndicatorType.SIMHASH, status, "00000000000000ff", 1);
    }

    private static CopiedLetterGuard guard() {
        return new CopiedLetterGuard(KnownCompanyFixtures.knownCompanies(
                KnownCompanyFixtures.company("Northwind Analytics", "northwind-analytics.com")), new FreeMailDomains());
    }
}
