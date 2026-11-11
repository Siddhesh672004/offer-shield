package com.offershield.company;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.AppUser;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcIdentity;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.auth.Role;
import com.offershield.common.audit.AuditLog;
import com.offershield.common.audit.AuditLogRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P8-01 (PRD §4 F8, §11; D-24, R-E): the global known-company list is ADMIN only, its domains are checked by the SSRF
 * guard and stored as lower-case registrable domains, a domain belongs to one company, and every change is audited
 * and visible to the analysis at once. Every company here is a per-test fixture (no seed rows, V-21) and is removed
 * afterwards, so the shared database stays clean for other tests.
 */
class KnownCompanyControllerTest extends IntegrationTest {

    /** Unique per test: lower-case letters and digits, usable in names and domains. */
    private final String token = "kc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @Autowired
    private OidcUserProvisioningService provisioningService;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private KnownCompanyRepository companies;
    @Autowired
    private KnownCompanyDomainRepository domains;
    @Autowired
    private KnownCompanies knownCompanies;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        // The session role is checked against the database on every request, so the admin needs a real ADMIN row.
        adminId = provisioningService.provision(new OidcIdentity("sub-" + UUID.randomUUID(), token + ".admin@example.com",
                true, "Test Admin")).userId();
        AppUser admin = users.findById(adminId).orElseThrow();
        admin.changeRole(Role.ADMIN);
        users.saveAndFlush(admin);
    }

    @AfterEach
    void removeFixtures() {
        companies.findAll().stream().filter(company -> company.getName().toLowerCase().startsWith(token))
                .forEach(companies::delete);
        knownCompanies.evict();
    }

    @Test
    void nonAdmin_403() throws Exception {
        UUID id = create(token + " Guarded", List.of(token + "-guarded.com"));

        for (Role role : List.of(Role.USER, Role.TPO)) {
            RequestPostProcessor someone = signedIn(role);
            mockMvc.perform(get("/api/known-companies").with(someone))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
            mockMvc.perform(post("/api/known-companies").with(someone).with(xsrf(mockMvc))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(companyJson(token + " Sneaky " + role, List.of(token + "-sneaky.com"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/known-companies/{id}", id).with(someone).with(xsrf(mockMvc)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(someone).with(xsrf(mockMvc))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(domainJson(token + "-extra.com")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/known-companies/{id}/domains/{domain}", id, token + "-guarded.com")
                            .with(someone).with(xsrf(mockMvc)))
                    .andExpect(status().isForbidden());
        }

        assertThat(companyNames()).containsExactly(token + " Guarded");
        assertThat(domainsOf(id)).containsExactly(token + "-guarded.com");
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(get("/api/known-companies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noXsrfToken_403() throws Exception {
        mockMvc.perform(post("/api/known-companies").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(token + " No Token", List.of(token + "-notoken.com"))))
                .andExpect(status().isForbidden());

        assertThat(companyNames()).isEmpty();
    }

    @Test
    void create_admin_201NameCleanedDomainsRegistrableLowercaseSorted() throws Exception {
        mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson("  " + token + "   Labs  ",
                                List.of("HR." + token + "-labs.COM", token + "-labs.com", " " + token + "labs.co.in. "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value(token + " Labs"))
                .andExpect(jsonPath("$.domains.length()").value(2))
                .andExpect(jsonPath("$.domains[0]").value(token + "-labs.com"))
                .andExpect(jsonPath("$.domains[1]").value(token + "labs.co.in"));
    }

    @Test
    void create_idnDomain_storedAsPunycode() throws Exception {
        String unicode = token + "-ląbs.com"; // ą
        String body = mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(token + " Unicode", List.of(unicode))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String stored = JsonPath.read(body, "$.domains[0]");
        assertThat(stored).isEqualTo(IDN.toASCII(unicode)).contains("xn--");
    }

    @Test
    void duplicateDomain_409() throws Exception {
        create(token + " First", List.of(token + "-shared.com"));

        mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(token + " Second",
                                List.of(token + "-second.com", "careers." + token.toUpperCase() + "-SHARED.com"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("DOMAIN_TAKEN"));

        assertThat(companyNames()).containsExactly(token + " First");
        assertThat(domains.existsByDomain(token + "-second.com")).isFalse();
    }

    @Test
    void duplicateName_ignoringCase_409() throws Exception {
        create(token + " Same Name", List.of(token + "-one.com"));

        mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(token.toUpperCase() + " SAME   name", List.of(token + "-two.com"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COMPANY_EXISTS"));

        assertThat(domains.existsByDomain(token + "-two.com")).isFalse();
    }

    @Test
    void duplicateName_accentVariant_409() throws Exception {
        create(token + " Zenvora", List.of(token + "-zenvora.com"));

        mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(token + " Zénvora", List.of(token + "-zenvora.in"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COMPANY_EXISTS"));
    }

    /** The unique keys behind the pre-checks: what stops two admins adding the same domain or name at once. */
    @Test
    void uniqueKeys_rejectDuplicatesWrittenAtOnce() throws Exception {
        UUID id = create(token + " Keyed", List.of(token + "-keyed.com"));

        assertThatThrownBy(() -> domains.saveAndFlush(KnownCompanyDomain.create(id, token + "-keyed.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> companies.saveAndFlush(KnownCompany.create(token.toUpperCase() + " KEYED")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "[::1]", "2130706433", "localhost", "https://kcinvalid.com", "kcinvalid.com:8080",
            "kcinvalid.com/careers", "hr@kcinvalid.com", "co.in", "kcinvalid.test", "intranet", "kc invalid.com"})
    void notAPublicDomain_400(String domain) throws Exception {
        mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(token + " Invalid", List.of(domain))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INVALID_DOMAIN"));

        assertThat(companyNames()).isEmpty();
    }

    @Test
    void freeMailDomain_422() throws Exception {
        // D-08: a free-mail provider is never a company's official domain (else a Gmail sender would look official).
        for (String domain : List.of("Gmail.com", "mail.yahoo.co.in")) {
            mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(companyJson(token + " Free Mail", List.of(token + "-ok.com", domain))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorCode").value("FREE_MAIL_DOMAIN"));
        }

        assertThat(companyNames()).isEmpty();
    }

    @Test
    void invalidBody_400() throws Exception {
        List<String> eleven = IntStream.range(0, 11).mapToObj(i -> token + "-d" + i + ".com").toList();
        for (String body : List.of(
                companyJson("  ", List.of(token + "-a.com")),
                companyJson("x", List.of(token + "-a.com")),
                companyJson("<script>" + token, List.of(token + "-a.com")),
                companyJson(token + " " + "n".repeat(100), List.of(token + "-a.com")),
                companyJson(token + " No Domains", List.of()),
                companyJson(token + " Blank Domain", List.of(" ")),
                companyJson(token + " Too Many", eleven),
                "{\"name\":\"" + token + " Null Domains\"}")) {
            mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }

        assertThat(companyNames()).isEmpty();
    }

    @Test
    void list_admin_sortedByNameWithDomains() throws Exception {
        UUID b = create(token + " B Company", List.of(token + "-b2.com", token + "-b1.com"));
        UUID a = create(token + " A Company", List.of(token + "-a.com"));

        String body = mockMvc.perform(get("/api/known-companies").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> rows = JsonPath.read(body, "$");
        List<Map<String, Object>> mine = rows.stream()
                .filter(row -> ((String) row.get("name")).startsWith(token)).toList();
        assertThat(mine).extracting(row -> row.get("id")).containsExactly(a.toString(), b.toString());
        assertThat(mine.get(1)).containsEntry("name", token + " B Company")
                .containsEntry("domains", List.of(token + "-b1.com", token + "-b2.com"));
    }

    @Test
    void delete_admin_204_companyAndDomainsGone() throws Exception {
        UUID id = create(token + " Doomed", List.of(token + "-doomed.com", token + "-doomed.in"));

        mockMvc.perform(delete("/api/known-companies/{id}", id).with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isNoContent());

        assertThat(companies.existsById(id)).isFalse();
        assertThat(domains.existsByDomain(token + "-doomed.com")).isFalse();
        assertThat(domains.existsByDomain(token + "-doomed.in")).isFalse();
    }

    @Test
    void unknownCompany_404() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(delete("/api/known-companies/{id}", unknown).with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        mockMvc.perform(post("/api/known-companies/{id}/domains", unknown).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson(token + "-nowhere.com")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/known-companies/{id}/domains/{domain}", unknown, token + "-nowhere.com")
                        .with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound());
        assertThat(domains.existsByDomain(token + "-nowhere.com")).isFalse();
    }

    @Test
    void addDomain_200_registrableSorted() throws Exception {
        UUID id = create(token + " Growing", List.of(token + "-z.com"));

        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson("Careers." + token + "-A.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.domains[0]").value(token + "-a.com"))
                .andExpect(jsonPath("$.domains[1]").value(token + "-z.com"));
    }

    @Test
    void addDomain_takenInvalidFreeMailOrEleventh_refused() throws Exception {
        create(token + " Owner", List.of(token + "-owned.com"));
        UUID id = create(token + " Taker", List.of(token + "-taker.com"));

        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson(token + "-owned.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DOMAIN_TAKEN"));
        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson(token + "-taker.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DOMAIN_TAKEN"));
        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson("169.254.169.254")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DOMAIN"));
        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson("outlook.com")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("FREE_MAIL_DOMAIN"));
        for (int i = 2; i <= KnownCompanyService.MAX_DOMAINS; i++) {
            mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                            .contentType(MediaType.APPLICATION_JSON).content(domainJson(token + "-n" + i + ".com")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson(token + "-eleventh.com")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_DOMAINS"));

        assertThat(domainsOf(id)).hasSize(KnownCompanyService.MAX_DOMAINS).doesNotContain(token + "-eleventh.com");
    }

    @Test
    void removeDomain_200_lastDomain409_otherCompanysDomain404() throws Exception {
        UUID other = create(token + " Other", List.of(token + "-other.com"));
        UUID id = create(token + " Shrinking", List.of(token + "-keep.com", token + "-drop.com"));

        mockMvc.perform(delete("/api/known-companies/{id}/domains/{domain}", id, token.toUpperCase() + "-DROP.com")
                        .with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domains.length()").value(1))
                .andExpect(jsonPath("$.domains[0]").value(token + "-keep.com"));
        mockMvc.perform(delete("/api/known-companies/{id}/domains/{domain}", id, token + "-keep.com")
                        .with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LAST_DOMAIN"));
        mockMvc.perform(delete("/api/known-companies/{id}/domains/{domain}", id, token + "-other.com")
                        .with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isNotFound());

        assertThat(domainsOf(id)).containsExactly(token + "-keep.com");
        assertThat(domainsOf(other)).containsExactly(token + "-other.com");
    }

    @Test
    void audited() throws Exception {
        UUID id = create(token + " Audited", List.of(token + "-audited.com"));
        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson(token + "-audited.in")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/known-companies/{id}/domains/{domain}", id, token + "-audited.in")
                        .with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/known-companies/{id}", id).with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isNoContent());

        assertThat(auditLogs.findAll()).filteredOn(log -> log.getActorId().equals(adminId))
                .extracting(AuditLog::getAction, AuditLog::getTargetId)
                .containsExactlyInAnyOrder(
                        tuple("KNOWN_COMPANY_CREATED", id),
                        tuple("KNOWN_COMPANY_DOMAIN_ADDED", id),
                        tuple("KNOWN_COMPANY_DOMAIN_REMOVED", id),
                        tuple("KNOWN_COMPANY_DELETED", id));
    }

    @Test
    void changes_visibleToAnalysisAtOnce() throws Exception {
        String domain = token + "-cached.com";
        assertThat(knownCompanies.isOfficialDomain(domain)).isFalse(); // loads and caches the list

        UUID id = create(token + " Cached", List.of(domain));
        assertThat(knownCompanies.isOfficialDomain(domain)).isTrue();

        mockMvc.perform(post("/api/known-companies/{id}/domains", id).with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content(domainJson(token + "-cached.in")))
                .andExpect(status().isOk());
        assertThat(knownCompanies.isOfficialDomain(token + "-cached.in")).isTrue();

        mockMvc.perform(delete("/api/known-companies/{id}/domains/{domain}", id, token + "-cached.in")
                        .with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isOk());
        assertThat(knownCompanies.isOfficialDomain(token + "-cached.in")).isFalse();

        mockMvc.perform(delete("/api/known-companies/{id}", id).with(admin()).with(xsrf(mockMvc)))
                .andExpect(status().isNoContent());
        assertThat(knownCompanies.isOfficialDomain(domain)).isFalse();
    }

    private RequestPostProcessor admin() {
        return signedIn(adminId, Role.ADMIN);
    }

    private UUID create(String name, List<String> domainNames) throws Exception {
        String body = mockMvc.perform(post("/api/known-companies").with(admin()).with(xsrf(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyJson(name, domainNames)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private List<String> companyNames() {
        return companies.findAll().stream().map(KnownCompany::getName)
                .filter(name -> name.toLowerCase().startsWith(token)).toList();
    }

    private List<String> domainsOf(UUID companyId) {
        return domains.findByCompanyIdOrderByDomainAsc(companyId).stream().map(KnownCompanyDomain::getDomain).toList();
    }

    private static String companyJson(String name, List<String> domainNames) {
        List<String> quoted = new ArrayList<>();
        for (String domain : domainNames) {
            quoted.add("\"" + domain + "\"");
        }
        return "{\"name\": \"" + name + "\", \"domains\": [" + quoted.stream().collect(Collectors.joining(", ")) + "]}";
    }

    private static String domainJson(String domain) {
        return "{\"domain\": \"" + domain + "\"}";
    }
}
