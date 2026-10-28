package com.offershield.submission;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.college.CollegeRepository;
import com.offershield.membership.MembershipRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-12: at most 5 uploads per user in 10 minutes, counted in MySQL; the 6th gets 429 with Retry-After. */
class UploadLimitTest extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sixthIn10Min_429() throws Exception {
        SubmissionFixtures fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        UUID user = fixtures.user();
        String firstKey = SubmissionFixtures.newKey();
        String firstId = null;
        for (int i = 0; i < 5; i++) {
            String key = i == 0 ? firstKey : SubmissionFixtures.newKey();
            String body = mockMvc.perform(fixtures.upload(user, key).param("text", SubmissionFixtures.LETTER + " #" + i))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            if (i == 0) {
                firstId = JsonPath.read(body, "$.submissionId");
            }
        }

        mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", SubmissionFixtures.LETTER + " #6"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_LIMIT"));
        assertThat(SubmissionFixtures.count(jdbc, user)).isEqualTo(5);

        // A retry of an upload that already went through is not a new upload.
        mockMvc.perform(fixtures.upload(user, firstKey).param("text", SubmissionFixtures.LETTER + " #0"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.submissionId").value(firstId));

        // Another user is unaffected.
        mockMvc.perform(fixtures.upload(fixtures.user(), SubmissionFixtures.newKey()).param("text", SubmissionFixtures.LETTER))
                .andExpect(status().isAccepted());
    }

    @Test
    void parallelUploadsAtTheLimit_neverMoreThanFive() throws Exception {
        // P13 (S3): the count and the insert are atomic per user, so letters sent at once cannot pass the limit.
        SubmissionFixtures fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        UUID user = fixtures.user();
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", SubmissionFixtures.LETTER + " #" + i))
                    .andExpect(status().isAccepted());
        }
        int parallel = 4;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(parallel)) {
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                var request = fixtures.upload(user, SubmissionFixtures.newKey())
                        .param("text", SubmissionFixtures.LETTER + " parallel " + i);
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(request).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
        }

        assertThat(statuses).containsOnly(202, 429);
        assertThat(statuses).filteredOn(code -> code == 202).hasSize(1);
        assertThat(SubmissionFixtures.count(jdbc, user)).isEqualTo(5);
    }
}
