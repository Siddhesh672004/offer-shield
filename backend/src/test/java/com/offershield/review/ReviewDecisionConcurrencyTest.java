package com.offershield.review;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.Role;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.IndicatorStatus;
import com.offershield.support.CaseFixtures;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.offershield.support.RealCsrf.xsrf;
import static com.offershield.support.TestPrincipals.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRD §13.8, F-06: two TPOs of the same college decide the same case at the same moment with the same version. The
 * case row is locked for the decision, so exactly one gets 200 and the other 409 (it sees the new version); there is
 * one audit row and the markers are confirmed once. Fake data only.
 */
class ReviewDecisionConcurrencyTest extends IntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private IndicatorService indicators;

    @Test
    void sameVersion_one200one409() throws Exception {
        CaseFixtures fixtures = new CaseFixtures(context, mockMvc);
        UUID college = fixtures.college();
        List<UUID> tpos = List.of(fixtures.tpo(college), fixtures.tpo(college));
        UUID student = fixtures.user();
        fixtures.approve(student, college);
        UUID submission = fixtures.analysed(student, college, CaseFixtures.domain(), List.of(CaseFixtures.key()),
                CaseFixtures.simhash());
        String body = mockMvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"" + submission + "\"}")
                        .with(signedIn(student, Role.USER)).with(xsrf(mockMvc)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(JsonPath.read(body, "$.id"));
        List<String> decisions = List.of("CONFIRMED_SCAM", "INCONCLUSIVE");

        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                UUID tpo = tpos.get(i);
                String decision = decisions.get(i);
                var xsrf = xsrf(mockMvc);
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/reviews/{id}/decision", caseId).header("If-Match", "\"0\"")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"decision\":\"" + decision + "\"}")
                                    .with(signedIn(tpo, Role.TPO)).with(xsrf))
                            .andReturn();
                }));
            }
            start.countDown();
            for (Future<MvcResult> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS).getResponse().getStatus());
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(fixtures.auditRowsFor(caseId)).isEqualTo(1);
        int winner = statuses.indexOf(200);
        boolean scamWon = decisions.get(winner).equals("CONFIRMED_SCAM");
        assertThat(indicators.markersOf(submission)).hasSize(scamWon ? 3 : 0)
                .allSatisfy(marker -> assertThat(marker.status()).isEqualTo(IndicatorStatus.CONFIRMED));
    }
}
