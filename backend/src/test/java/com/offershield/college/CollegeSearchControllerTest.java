package com.offershield.college;

import com.offershield.auth.Role;
import com.offershield.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.offershield.support.TestPrincipals.signedIn;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollegeSearchControllerTest extends IntegrationTest {

    /** Unique per test, so rows left by other tests in the shared database never match. */
    private final String token = "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void search_25Matches_max20OrderedByName() throws Exception {
        IntStream.rangeClosed(1, 25).forEach(i -> colleges.save(
                College.create(token + " College %02d".formatted(i), "Pune")));

        mockMvc.perform(get("/api/colleges").param("q", token).with(signedIn(Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].name").value(token + " College 01"))
                .andExpect(jsonPath("$[0].city").value("Pune"))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[19].name").value(token + " College 20"));
    }

    @Test
    void search_inactiveCollege_activeOnly() throws Exception {
        colleges.save(College.create(token + " Active College", "Nagpur"));
        College inactive = colleges.save(College.create(token + " Closed College", "Nagpur"));
        deactivate(inactive);

        mockMvc.perform(get("/api/colleges").param("q", token).with(signedIn(Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(token + " Active College"));
    }

    @Test
    void search_mixedCaseMiddleOfName_caseInsensitiveContains() throws Exception {
        College college = colleges.save(College.create(token + " Alpha Institute", null));
        String query = token.substring(3).toUpperCase(Locale.ROOT) + " aLPHA inst";

        mockMvc.perform(get("/api/colleges").param("q", query).with(signedIn(Role.TPO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(college.getId().toString()))
                .andExpect(jsonPath("$[0].city").doesNotExist());
    }

    @Test
    void search_likeWildcardInQuery_matchedLiterally() throws Exception {
        colleges.save(College.create(token + " 100% Placement College", "Mumbai"));
        colleges.save(College.create(token + " 100x Placement College", "Mumbai"));

        mockMvc.perform(get("/api/colleges").param("q", token + " 100%").with(signedIn(Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(token + " 100% Placement College"));
    }

    @Test
    void search_noQuery_okAtMost20() throws Exception {
        colleges.save(College.create(token + " Any College", "Pune"));

        mockMvc.perform(get("/api/colleges").with(signedIn(Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(allOf(greaterThanOrEqualTo(1), lessThanOrEqualTo(20))));
    }

    @Test
    void search_queryLongerThan200_400() throws Exception {
        mockMvc.perform(get("/api/colleges").param("q", "x".repeat(201)).with(signedIn(Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void search_noSession_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/colleges").param("q", token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    /** College has no deactivate operation yet (admins only create colleges in P3), so flip the status directly. */
    private void deactivate(College college) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createQuery("update College c set c.status = :status where c.id = :id")
                .setParameter("status", CollegeStatus.INACTIVE)
                .setParameter("id", college.getId())
                .executeUpdate());
    }
}
