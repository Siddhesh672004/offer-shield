package com.offershield.registry;

import com.offershield.auth.Role;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-13 Revert over HTTP: {@code POST /api/admin/indicators/{id}/revert} with the marker's version sends a CONFIRMED
 * marker back to UNVERIFIED (its reporters still count) and writes {@code INDICATOR_REVERTED} (R-E); a stale version
 * or a marker that is not confirmed → 409; ADMIN only. Random keys only.
 */
class RevertTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private IndicatorService indicators;

    private CaseFixtures fixtures;
    private UUID admin;
    private IndicatorView confirmed;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new CaseFixtures(context, mockMvc);
        admin = fixtures.admin();
        UUID reporter = fixtures.user();
        UUID submission = fixtures.analysed(reporter, null, null, List.of(CaseFixtures.key()), null);
        indicators.reportScam(submission, reporter);
        confirmed = indicators.confirm(submission, admin).get(0);
    }

    @Test
    void confirmedToUnverified_audited() throws Exception {
        revert(confirmed.id(), "\"" + confirmed.version() + "\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + (confirmed.version() + 1) + "\""))
                .andExpect(jsonPath("$.id").value(confirmed.id().toString()))
                .andExpect(jsonPath("$.type").value("PHONE"))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.reporterCount").value(1))
                .andExpect(jsonPath("$.confirmedAt").doesNotExist())
                .andExpect(jsonPath("$.version").value(confirmed.version() + 1));

        assertThat(indicators.find(confirmed.id())).hasValueSatisfying(marker ->
                assertThat(marker.status()).isEqualTo(IndicatorStatus.UNVERIFIED));
        assertThat(fixtures.auditRows(admin, "INDICATOR_REVERTED", confirmed.id())).isEqualTo(1);
    }

    @Test
    void stale_409() throws Exception {
        revert(confirmed.id(), "\"" + (confirmed.version() - 1) + "\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));

        assertThat(indicators.find(confirmed.id())).hasValueSatisfying(marker ->
                assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED));
        assertThat(fixtures.auditRowsFor(confirmed.id())).isZero();
    }

    @Test
    void notConfirmed_409() throws Exception {
        revert(confirmed.id(), "\"" + confirmed.version() + "\"").andExpect(status().isOk());

        revert(confirmed.id(), "\"" + (confirmed.version() + 1) + "\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOT_CONFIRMED"));
    }

    @Test
    void unknown_404_missingIfMatch_428() throws Exception {
        revert(UUID.randomUUID(), "0").andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/indicators/{id}/revert", confirmed.id())
                        .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void nonAdmin_403() throws Exception {
        UUID user = fixtures.user();
        UUID tpo = fixtures.tpo(fixtures.college());

        for (var principal : List.of(signedIn(user, Role.USER), signedIn(tpo, Role.TPO))) {
            mockMvc.perform(post("/api/admin/indicators/{id}/revert", confirmed.id())
                            .header("If-Match", "\"" + confirmed.version() + "\"")
                            .with(principal).with(xsrf(mockMvc)))
                    .andExpect(status().isForbidden());
        }
        assertThat(indicators.find(confirmed.id())).hasValueSatisfying(marker ->
                assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED));
    }

    private ResultActions revert(UUID indicatorId, String ifMatch) throws Exception {
        return mockMvc.perform(post("/api/admin/indicators/{id}/revert", indicatorId).header("If-Match", ifMatch)
                .with(signedIn(admin, Role.ADMIN)).with(xsrf(mockMvc)));
    }
}
