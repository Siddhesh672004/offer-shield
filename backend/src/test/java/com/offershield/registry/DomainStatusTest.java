package com.offershield.registry;

import com.offershield.auth.Role;
import com.offershield.company.CreateKnownCompanyRequest;
import com.offershield.company.KnownCompanyService;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.UUID;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P10-05 (PRD §11; D-08): {@code GET /api/registry/domains/{domain}} for any signed-in user. Confirmed → "Confirmed
 * scam"; unconfirmed → "Reported as a scam by N users (not verified)" with the distinct count; no live marker → NONE;
 * free-mail → NOT_TRACKED; a subdomain is looked up as its registrable domain; IPs, localhost and ports → 400; never
 * who reported. Random domains only.
 */
class DomainStatusTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private IndicatorService indicators;
    @Autowired
    private KnownCompanyService knownCompanies;

    private CaseFixtures fixtures;
    private UUID viewer;

    @BeforeEach
    void setUp() {
        fixtures = new CaseFixtures(context, mockMvc);
        viewer = fixtures.user();
    }

    @Test
    void reportedByTwo_unverifiedWithCount() throws Exception {
        String domain = CaseFixtures.domain();
        UUID a = fixtures.user();
        UUID b = fixtures.user();
        indicators.reportScam(fixtures.analysed(a, null, domain, List.of(), null), a);
        indicators.reportScam(fixtures.analysed(b, null, domain, List.of(), null), b);

        String body = mockMvc.perform(get("/api/registry/domains/{domain}", domain).with(signedIn(viewer, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.reporterCount").value(2))
                .andExpect(jsonPath("$.message").value("Reported as a scam by 2 users (not verified)"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(a.toString()).doesNotContain(fixtures.email(a));
    }

    @Test
    void confirmed_subdomainUsesRegistrable() throws Exception {
        String domain = CaseFixtures.domain();
        UUID owner = fixtures.user();
        indicators.confirm(fixtures.analysed(owner, null, domain, List.of(), null), fixtures.admin());

        UUID tpo = fixtures.tpo(fixtures.college());
        mockMvc.perform(get("/api/registry/domains/{domain}", "HR.Careers." + domain)
                        .with(signedIn(tpo, Role.TPO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reporterCount").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        "Confirmed scam: a TPO or the admin confirmed a scam sent from this domain."));
    }

    @Test
    void officialDomain_markerStoredBeforeListing_notTracked() throws Exception {
        // P13 (test gap, D-08): a domain confirmed as a scam marker and listed later as a company's official domain is
        // "not tracked" on the public endpoint, never "Confirmed scam".
        String domain = CaseFixtures.domain();
        UUID owner = fixtures.user();
        UUID admin = fixtures.admin();
        indicators.confirm(fixtures.analysed(owner, null, domain, List.of(), null), admin);
        UUID company = knownCompanies.create(new CreateKnownCompanyRequest("P13 Listed Later " + UUID.randomUUID(),
                List.of(domain)), admin).id();
        try {
            mockMvc.perform(get("/api/registry/domains/{domain}", domain).with(signedIn(viewer, Role.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("NOT_TRACKED"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Confirmed scam"))));
        } finally {
            knownCompanies.delete(company, admin);
        }
    }

    @Test
    void unknownOrRejected_none() throws Exception {
        String rejected = CaseFixtures.domain();
        UUID owner = fixtures.user();
        UUID submission = fixtures.analysed(owner, null, rejected, List.of(), null);
        indicators.reportScam(submission, owner);
        indicators.withdrawReport(submission, owner);

        for (String domain : List.of(CaseFixtures.domain(), rejected)) {
            mockMvc.perform(get("/api/registry/domains/{domain}", domain).with(signedIn(viewer, Role.USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("NONE"))
                    .andExpect(jsonPath("$.message").value(
                            "No scam reports for this domain. This does not prove that an offer from it is genuine."));
        }
    }

    @Test
    void freeMail_notTracked() throws Exception {
        mockMvc.perform(get("/api/registry/domains/{domain}", "gmail.com").with(signedIn(viewer, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_TRACKED"))
                .andExpect(jsonPath("$.reporterCount").doesNotExist());
    }

    @Test
    void invalid_400() throws Exception {
        for (String raw : List.of("127.0.0.1", "localhost", "evil.com:8080", "x.example", "2130706433")) {
            mockMvc.perform(get("/api/registry/domains/{domain}", raw).with(signedIn(viewer, Role.USER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_DOMAIN"));
        }
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(get("/api/registry/domains/{domain}", "gmail.com")).andExpect(status().isUnauthorized());
    }
}
