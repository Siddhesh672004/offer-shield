package com.offershield.registry;

import com.jayway.jsonpath.JsonPath;
import com.offershield.analysis.SubmissionCreatedListener;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.common.events.Topics;
import com.offershield.company.CreateKnownCompanyRequest;
import com.offershield.company.KnownCompanyService;
import com.offershield.submission.SubmissionStatusListener;
import com.offershield.support.IntegrationTest;
import com.offershield.support.KafkaRecords;
import com.offershield.support.LookupStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P9 end to end on the embedded broker (PRD §4 F7 done-when, §5.3, §7.1–7.3; D-20 fan-out): upload → analysis →
 * {@code analysis.completed} → {@code registry-snapshot} stores the snapshot; a reviewer confirms user A's scam through
 * the registry service (P10's call, here with test data) → user B's letter with the same phone number is High by C10.
 * Also: a copy of a confirmed letter is High by C11 unless it comes from the official domain of the company the letter
 * names (then +20, "wording resembles a reported scam"), and an unconfirmed report only adds +10 as "Reported as a scam
 * by 1 user (not verified)". Fictional letters, fake numbers and made-up domains only (05 §4).
 */
class RegistryPipelineIT extends IntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Instant OLD = Instant.now().minus(Duration.ofDays(4000));
    private static final List<String> CONSUMERS = List.of(SubmissionCreatedListener.ID, SubmissionStatusListener.ID,
            ReportSnapshotListener.ID);

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private ReportSnapshotService snapshots;
    @Autowired
    private IndicatorService indicators;
    @Autowired
    private KnownCompanyService knownCompanies;
    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private final List<UUID> companies = new ArrayList<>();
    private final Map<UUID, UUID> owners = new HashMap<>();
    private RegistryTestData data;
    private UUID reviewer;

    @BeforeEach
    void startConsumers() {
        data = new RegistryTestData(provisioning, mockMvc, snapshots);
        reviewer = data.user();
        CONSUMERS.forEach(id -> registry.getListenerContainer(id).start());
    }

    @AfterEach
    void stopConsumers() {
        CONSUMERS.forEach(id -> registry.getListenerContainer(id).stop());
        companies.forEach(id -> knownCompanies.delete(id, reviewer));
    }

    @Test
    void scamConfirmedAtUserA_flaggedForUserB_c10High() throws Exception {
        String phone = phone();
        String scamDomain = healthy("scam-p9-" + token(8) + ".com");
        UUID userA = data.user();
        UUID userB = data.user();
        UUID submissionA = analyse(userA, "Dear Candidate,\n\nYou are selected as a Remote Data Associate at Example "
                + "Talent Hub (fictional). Complete the onboarding today: pay the refundable kit deposit of Rs. 3,000 "
                + "and send the receipt to our onboarding officer on " + phone + ". Seats are limited and the offer "
                + "closes this evening, so finish the payment before you sign the letter.\n\nOnboarding Desk\n",
                "hr@" + scamDomain);
        awaitSnapshot(submissionA);

        List<IndicatorView> confirmed = indicators.confirm(submissionA, reviewer);
        assertThat(confirmed).extracting(IndicatorView::type).contains(IndicatorType.DOMAIN, IndicatorType.PHONE);

        UUID submissionB = analyse(userB, "Dear Candidate,\n\nThank you for attending the interview. For your joining "
                + "formalities please call our HR desk on " + phone + ".\n\nRegards,\nHR Team\n", null);
        String report = report(userB, submissionB);

        assertThat((String) JsonPath.read(report, "$.band")).isEqualTo("HIGH");
        assertThat((Boolean) JsonPath.read(report, "$.hardOverride")).isTrue();
        Map<String, Object> c10 = signal(report, "C10");
        assertThat(c10).containsEntry("outcome", "TRIGGERED").containsEntry("hardOverride", true)
                .containsEntry("sourceKind", 4).containsEntry("aiExtracted", false)
                .containsEntry("title", "Confirmed scam match");
        assertThat((String) c10.get("explanation")).startsWith("Confirmed scam:");
        assertThat((String) c10.get("evidence")).contains("a phone number").doesNotContain(digits(phone));
        assertThat(signal(report, "C1")).containsEntry("outcome", "PASSED");
        assertThat(signal(report, "C12")).containsEntry("outcome", "PASSED");
        assertThat((Boolean) JsonPath.read(report, "$.registry.confirmedScam")).isTrue();
        assertThat((Integer) JsonPath.read(report, "$.registry.reportedBy")).isZero();
        assertThat(report).doesNotContain(digits(phone));

        // The completion carries the phone's HMAC key, never the number, and the registry keeps B's snapshot too.
        String completed = KafkaRecords.value(KafkaRecords.readAll(Topics.ANALYSIS_COMPLETED, submissionB.toString(),
                1, Duration.ofSeconds(15)).get(0));
        List<String> phoneKeys = JsonPath.read(completed, "$.payload.phoneHmacs");
        assertThat(phoneKeys).singleElement().satisfies(key -> assertThat(key).matches("[0-9a-f]{64}"));
        assertThat((Boolean) JsonPath.read(completed, "$.payload.indicatorsComplete")).isTrue();
        assertThat(completed).doesNotContain(digits(phone));
        ReportSnapshotView snapshotB = awaitSnapshot(submissionB);
        assertThat(snapshotB.phoneHmacs()).isEqualTo(phoneKeys);
        assertThat(snapshotB.band()).isEqualTo("HIGH");
        assertThat(snapshotB.signals()).anySatisfy(signal -> assertThat(signal.code()).isEqualTo("C10"));
    }

    @Test
    void copiedLetter_c11High_officialSenderOnlyWarned() throws Exception {
        String name = "Zenvora " + token(6);
        String official = healthy("zenvora-" + token(8) + ".com");
        companies.add(knownCompanies.create(new CreateKnownCompanyRequest(name, List.of(official)), reviewer).id());
        UUID userA = data.user();
        UUID submissionA = analyse(userA, genuineStyle(name, phone()), "hr@" + healthy("careers-desk-" + token(8)
                + ".com"));
        awaitSnapshot(submissionA);
        indicators.confirm(submissionA, reviewer);

        // A re-sent copy (other phone number) from another non-official domain: C11 forces High.
        UUID copy = analyse(data.user(), genuineStyle(name, phone()), "jobs@" + healthy("hiring-desk-" + token(8)
                + ".com"));
        String copyReport = report(null, copy);
        Map<String, Object> c11 = signal(copyReport, "C11");
        assertThat(c11).containsEntry("outcome", "TRIGGERED").containsEntry("hardOverride", true)
                .containsEntry("sourceKind", 4);
        assertThat((String) JsonPath.read(copyReport, "$.band")).isEqualTo("HIGH");
        assertThat((Boolean) JsonPath.read(copyReport, "$.registry.confirmedScam")).isTrue();

        // The same wording from the company's official domain (named in the letter): +20 warning, never High.
        UUID genuine = analyse(data.user(), genuineStyle(name, phone()), "talent@" + official);
        String genuineReport = report(null, genuine);
        Map<String, Object> warning = signal(genuineReport, "C11");
        assertThat(warning).containsEntry("outcome", "TRIGGERED").containsEntry("hardOverride", false)
                .containsEntry("points", 20);
        assertThat((String) warning.get("explanation")).startsWith("Wording resembles a reported scam.");
        assertThat(signal(genuineReport, "C3")).containsEntry("outcome", "PASSED");
        assertThat(signal(genuineReport, "C10")).containsEntry("outcome", "PASSED");
        assertThat((Boolean) JsonPath.read(genuineReport, "$.hardOverride")).isFalse();
        assertThat((String) JsonPath.read(genuineReport, "$.band")).isEqualTo("LOW");
        assertThat((Integer) JsonPath.read(genuineReport, "$.score")).isEqualTo(20);
        assertThat((Boolean) JsonPath.read(genuineReport, "$.registry.confirmedScam")).isFalse();
        assertThat((Boolean) JsonPath.read(genuineReport, "$.registry.wordingWarning")).isTrue();
    }

    @Test
    void plantedOfficialAddressBeforeGmail_copyStillHigh_noDomainMarker() throws Exception {
        // P13 (security review S2): with no typed sender email, D-33.6 takes the first address in the letter, which
        // its author chose. A confirmed scam copy that writes the company's official address first and a Gmail address
        // later must stay High (C11), C3 must not pass, and a domain picked that way is no scam marker when the letter
        // has other email domains.
        String name = "Zenvora " + token(6);
        String official = healthy("zenvora-" + token(8) + ".com");
        companies.add(knownCompanies.create(new CreateKnownCompanyRequest(name, List.of(official)), reviewer).id());
        String contacts = "talent@" + official + " or send the documents to joining.desk." + token(5) + "@gmail.com";
        UUID original = analyse(data.user(), genuineStyle(name, contacts), "hr@" + healthy("careers-desk-" + token(8)
                + ".com"));
        awaitSnapshot(original);
        indicators.confirm(original, reviewer);

        UUID copy = analyse(data.user(), genuineStyle(name, contacts), null);
        String copyReport = report(null, copy);

        assertThat((String) JsonPath.read(copyReport, "$.senderSource")).isEqualTo("LETTER");
        assertThat((String) JsonPath.read(copyReport, "$.senderDomain")).isEqualTo(official);
        assertThat(signal(copyReport, "C11")).containsEntry("outcome", "TRIGGERED").containsEntry("hardOverride", true);
        assertThat(signal(copyReport, "C3")).containsEntry("outcome", "NOT_APPLICABLE");
        assertThat((String) JsonPath.read(copyReport, "$.band")).isEqualTo("HIGH");
        ReportSnapshotView copySnapshot = awaitSnapshot(copy);
        assertThat(copySnapshot.senderSource()).isEqualTo("LETTER");
        assertThat(copySnapshot.letterEmailDomains()).isEqualTo(2);

        // An unlisted domain written first, then a Gmail address: reporting it adds no DOMAIN marker for that domain.
        String innocent = healthy("innocent-firm-" + token(8) + ".com");
        UUID reporter = data.user();
        UUID planted = analyse(reporter, "Dear Candidate,\n\nYou are selected. Our partner hr@" + innocent + " will "
                + "share the joining kit; send the fee receipt to kit.desk." + token(5) + "@gmail.com.\n\nRegards,\n"
                + "Hiring Desk\n", null);
        ReportSnapshotView plantedSnapshot = awaitSnapshot(planted);
        assertThat(plantedSnapshot.domain()).isEqualTo(innocent);
        assertThat(plantedSnapshot.senderSource()).isEqualTo("LETTER");

        List<IndicatorView> markers = indicators.reportScam(planted, reporter);

        assertThat(markers).extracting(IndicatorView::type).doesNotContain(IndicatorType.DOMAIN);
    }

    @Test
    void reportedNotConfirmed_c12PlusTenNeverHigh() throws Exception {
        String phone = phone();
        UUID reporter = data.user();
        UUID reported = analyse(reporter, "Dear Candidate,\n\nCongratulations on your selection. Message our "
                + "recruiter on " + phone + " to receive your joining letter.\n\nRegards,\nRecruitment Team\n", null);
        awaitSnapshot(reported);
        indicators.reportScam(reported, reporter);

        UUID later = analyse(data.user(), "Dear Candidate,\n\nThank you for your application. Our coordinator will "
                + "call you from " + phone + " about the next round.\n\nRegards,\nTalent Team\n", null);
        String report = report(null, later);

        Map<String, Object> c12 = signal(report, "C12");
        assertThat(c12).containsEntry("outcome", "TRIGGERED").containsEntry("points", 10)
                .containsEntry("hardOverride", false).containsEntry("sourceKind", 5);
        assertThat((String) c12.get("explanation")).startsWith("Reported as a scam by 1 user (not verified).");
        assertThat(signal(report, "C10")).containsEntry("outcome", "PASSED");
        assertThat((Boolean) JsonPath.read(report, "$.hardOverride")).isFalse();
        assertThat((String) JsonPath.read(report, "$.band")).isEqualTo("LOW");
        assertThat((Integer) JsonPath.read(report, "$.registry.reportedBy")).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(report, "$.registry.confirmedScam")).isFalse();
    }

    /** A fictional genuine-style letter of {@code company}, well over 50 words, whose contact number varies. */
    private static String genuineStyle(String company, String phone) {
        return company + " Private Limited (a fictional company)\n\nDear Candidate,\n\nCongratulations! Following "
                + "your interviews with our engineering panel, we are pleased to offer you the position of Graduate "
                + "Engineer Trainee in our Pune office. Your annual cost to company will be Rs. 6,00,000, paid monthly "
                + "into your bank account. Your joining date is 1 December, and your reporting manager will contact "
                + "you a week before. Please confirm your acceptance within 15 days. For joining formalities call "
                + "our HR desk on " + phone + " during working hours.\n\nWe look forward to working with you.\n\n"
                + "Regards,\nTalent Acquisition Team\n" + company + " Private Limited\n";
    }

    private UUID analyse(UUID user, String text, String senderEmail) throws Exception {
        var request = multipart("/api/submissions").param("text", text)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(signedIn(user, Role.USER)).with(xsrf(mockMvc));
        if (senderEmail != null) {
            request.param("senderEmail", senderEmail);
        }
        String body = mockMvc.perform(request).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.submissionId"));
        owners.put(id, user);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).until(() -> {
            String submission = mockMvc.perform(get("/api/submissions/{id}", id).with(signedIn(user, Role.USER)))
                    .andReturn().getResponse().getContentAsString();
            return !"QUEUED".equals(JsonPath.read(submission, "$.status"));
        });
        return id;
    }

    private String report(UUID user, UUID submission) throws Exception {
        UUID owner = user == null ? owners.get(submission) : user;
        return mockMvc.perform(get("/api/reports/{id}", submission).with(signedIn(owner, Role.USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private ReportSnapshotView awaitSnapshot(UUID submission) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> snapshots.find(submission).isPresent());
        return snapshots.find(submission).orElseThrow();
    }

    private static Map<String, Object> signal(String report, String code) {
        List<Map<String, Object>> signals = JsonPath.read(report, "$.signals[?(@.code == '" + code + "')]");
        assertThat(signals).as(code).hasSize(1);
        return signals.get(0);
    }

    /** An old domain with MX and DMARC, so C5/C6 add nothing. */
    private static String healthy(String domain) {
        LookupStubs.RDAP.registered(domain, OLD);
        LookupStubs.DNS.mx(domain, "mx." + domain).txt("_dmarc." + domain, "v=DMARC1; p=reject");
        return domain;
    }

    /** A fake Indian mobile number +91 99999 05xxx–09xxx (05 §4), random so tests never share markers. */
    private static String phone() {
        return "+91 99999 0" + (5000 + RANDOM.nextInt(5000));
    }

    private static String digits(String phone) {
        return phone.replaceAll("\\D", "").substring(2);
    }

    private static String token(int length) {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < length; i++) {
            token.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return token.toString();
    }
}
