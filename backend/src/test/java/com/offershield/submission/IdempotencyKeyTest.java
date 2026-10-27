package com.offershield.submission;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.college.CollegeRepository;
import com.offershield.membership.MembershipRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-33.2 / PRD §13.4: a repeated Idempotency-Key returns the same submission; MySQL 8.4 enforces it under races. */
class IdempotencyKeyTest extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private JdbcTemplate jdbc;

    private SubmissionFixtures fixtures;
    private UUID user;

    @BeforeEach
    void setUp() {
        fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        user = fixtures.user();
    }

    @Test
    void replay_sameId() throws Exception {
        String key = SubmissionFixtures.newKey();

        String first = accepted(fixtures.upload(user, key).param("text", SubmissionFixtures.LETTER));
        String second = accepted(fixtures.upload(user, key).param("text", SubmissionFixtures.LETTER));

        assertThat(second).isEqualTo(first);
        assertThat(SubmissionFixtures.count(jdbc, user)).isEqualTo(1);
    }

    @Test
    void replayOfPdf_sameId() throws Exception {
        String key = SubmissionFixtures.newKey();
        byte[] pdf = TestPdfs.onePage();

        String first = accepted(fixtures.upload(user, key).file(SubmissionFixtures.pdf(pdf)));
        String second = accepted(fixtures.upload(user, key).file(SubmissionFixtures.pdf(pdf)));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void differentContent_422() throws Exception {
        String key = SubmissionFixtures.newKey();
        accepted(fixtures.upload(user, key).param("text", SubmissionFixtures.LETTER));

        mockMvc.perform(fixtures.upload(user, key).param("text", SubmissionFixtures.LETTER + " Changed."))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(fixtures.upload(user, key).param("text", SubmissionFixtures.LETTER)
                        .param("recruiterPhone", SubmissionFixtures.PHONE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(SubmissionFixtures.count(jdbc, user)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentUsers_twoSubmissions() throws Exception {
        String key = SubmissionFixtures.newKey();
        UUID other = fixtures.user();

        String mine = accepted(fixtures.upload(user, key).param("text", SubmissionFixtures.LETTER));
        String theirs = accepted(fixtures.upload(other, key).param("text", SubmissionFixtures.LETTER));

        assertThat(theirs).isNotEqualTo(mine);
    }

    @Test
    void missingKey_400() throws Exception {
        mockMvc.perform(fixtures.upload(user, null).param("text", SubmissionFixtures.LETTER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(SubmissionFixtures.count(jdbc, user)).isZero();
    }

    @Test
    void concurrentDoubleClick_oneRowSameId() throws Exception {
        String key = SubmissionFixtures.newKey();
        int clicks = 4;
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> responses = new ArrayList<>();
            for (int i = 0; i < clicks; i++) {
                MockMultipartHttpServletRequestBuilder request = fixtures.upload(user, key)
                        .param("text", SubmissionFixtures.LETTER);
                Callable<MockHttpServletResponse> click = () -> {
                    start.await();
                    return mockMvc.perform(request).andReturn().getResponse();
                };
                responses.add(pool.submit(click));
            }
            start.countDown();

            List<String> ids = new ArrayList<>();
            for (Future<MockHttpServletResponse> response : responses) {
                MockHttpServletResponse result = response.get(60, TimeUnit.SECONDS);
                assertThat(result.getStatus()).isEqualTo(202);
                ids.add(JsonPath.read(result.getContentAsString(), "$.submissionId"));
            }
            assertThat(ids).hasSize(clicks).containsOnly(ids.get(0));
            assertThat(SubmissionFixtures.count(jdbc, user)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDoubleClickAtTheFifthUpload_allGetTheSameId() throws Exception {
        // P13 (CR2): with 4 uploads already made, clicks of the same letter with the same key must all be answered
        // with the one submission; a click whose insert runs after the first commit must not get 429.
        for (int i = 0; i < 4; i++) {
            accepted(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", SubmissionFixtures.LETTER + " #" + i));
        }
        String key = SubmissionFixtures.newKey();
        int clicks = 4;
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> responses = new ArrayList<>();
            for (int i = 0; i < clicks; i++) {
                MockMultipartHttpServletRequestBuilder request = fixtures.upload(user, key)
                        .param("text", SubmissionFixtures.LETTER);
                Callable<MockHttpServletResponse> click = () -> {
                    start.await();
                    return mockMvc.perform(request).andReturn().getResponse();
                };
                responses.add(pool.submit(click));
            }
            start.countDown();

            List<String> ids = new ArrayList<>();
            for (Future<MockHttpServletResponse> response : responses) {
                MockHttpServletResponse result = response.get(60, TimeUnit.SECONDS);
                assertThat(result.getStatus()).isEqualTo(202);
                ids.add(JsonPath.read(result.getContentAsString(), "$.submissionId"));
            }
            assertThat(ids).hasSize(clicks).containsOnly(ids.get(0));
            assertThat(SubmissionFixtures.count(jdbc, user)).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    private String accepted(MockMultipartHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/submissions/")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.submissionId");
    }
}
