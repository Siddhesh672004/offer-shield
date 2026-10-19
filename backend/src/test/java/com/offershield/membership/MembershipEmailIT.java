package com.offershield.membership;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.college.CollegeRepository;
import com.offershield.support.MailIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-22 over real SMTP (GreenMail): the student is emailed exactly once, only after the decision commits; a
 * rolled-back decision sends nothing; an SMTP outage leaves the decision committed. No personal data is logged.
 */
class MembershipEmailIT extends MailIntegrationTest {

    private static final String REASON = "Department R-555 is not part of this drive";

    @Autowired
    private MembershipService membershipService;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CollegeRepository colleges;

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> logs;
    private MembershipFixtures fixtures;
    private UUID college;
    private UUID tpo;
    private UUID student;
    private UUID membershipId;

    @BeforeEach
    void setUp() {
        fixtures = new MembershipFixtures(provisioning, users, colleges);
        college = fixtures.college("Email");
        tpo = fixtures.tpo(college);
        student = fixtures.student();
        membershipId = membershipService.request(student,
                new CreateMembershipRequest(college, "R-555", "Electronics")).id();
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(logs);
    }

    @Test
    void approve_committed_exactlyOneEmailAfterCommit() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            membershipService.decide(tpo, membershipId, 0L, new MembershipDecisionRequest(MembershipDecision.APPROVE, null));
            assertThat(GREEN_MAIL.getReceivedMessages()).as("nothing is sent before commit").isEmpty();
        });

        assertThat(GREEN_MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
        assertThat(GREEN_MAIL.waitForIncomingEmail(500, 2)).as("exactly one email").isFalse();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()).extracting(Object::toString).containsExactly(fixtures.email(student));
        assertThat(received[0].getContent().toString()).contains("was approved");
        assertNoPersonalDataLogged();
    }

    @Test
    void decisionRolledBack_noEmail() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            membershipService.decide(tpo, membershipId, 0L,
                    new MembershipDecisionRequest(MembershipDecision.REJECT, REASON));
            status.setRollbackOnly();
        });

        assertThat(GREEN_MAIL.waitForIncomingEmail(1_000, 1)).isFalse();
        Membership saved = memberships.findById(membershipId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    void smtpDown_decisionKept() throws Exception {
        GREEN_MAIL.stop();
        try {
            mockMvc.perform(post("/api/memberships/{id}/decision", membershipId)
                            .with(signedIn(tpo, Role.TPO)).with(xsrf(mockMvc)).header("If-Match", "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"REJECT\",\"reason\":\"" + REASON + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
        } finally {
            GREEN_MAIL.start();
        }

        assertThat(memberships.findById(membershipId).orElseThrow().getStatus()).isEqualTo(MembershipStatus.REJECTED);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(line -> assertThat(line).startsWith("email failed: "));
        assertNoPersonalDataLogged();
    }

    /** Every logger except GreenMail's own (the fake SMTP server logs the mailbox it creates). */
    private void assertNoPersonalDataLogged() {
        String email = fixtures.email(student);
        String name = fixtures.name(student);
        assertThat(logs.list).filteredOn(event -> !event.getLoggerName().startsWith("com.icegreen")).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(email).doesNotContain(name).doesNotContain("R-555").doesNotContain(REASON);
            if (event.getThrowableProxy() != null) {
                assertThat(event.getThrowableProxy().getMessage()).doesNotContain(email);
            }
        });
    }
}
