package com.offershield.membership;

import com.offershield.auth.AppUserRepository;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.college.CollegeRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6 schema and JPA mapping on MySQL 8.4: one row per student, scoped lookups, pending queue order. */
class MembershipRepositoryTest extends IntegrationTest {

    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private JdbcTemplate jdbc;

    private MembershipFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new MembershipFixtures(provisioning, users, colleges);
    }

    @Test
    void save_readBack_allColumnsAndVersionZero() {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Mapping");

        Membership saved = memberships.saveAndFlush(Membership.request(student, college, "CS-001", "Computer Engineering"));

        Membership found = memberships.findById(saved.getId()).orElseThrow();
        assertThat(found.getUserId()).isEqualTo(student);
        assertThat(found.getCollegeId()).isEqualTo(college);
        assertThat(found.getRollNo()).isEqualTo("CS-001");
        assertThat(found.getDepartment()).isEqualTo("Computer Engineering");
        assertThat(found.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(found.getVersion()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM membership WHERE roll_no = 'CS-001' AND LOWER(HEX(id)) = ?",
                String.class, saved.getId().toString().replace("-", ""))).isEqualTo("PENDING");
    }

    @Test
    void save_secondRowForSameStudent_rejectedByUniqueKey() {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Unique");
        memberships.saveAndFlush(Membership.request(student, college, "CS-002", "Computer Engineering"));

        assertThatThrownBy(() -> memberships.saveAndFlush(
                Membership.request(student, college, "CS-003", "Computer Engineering")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decide_savedTwice_versionIncrementsAndDecisionStored() {
        UUID student = fixtures.student();
        UUID college = fixtures.college("Decision");
        UUID tpo = fixtures.tpo(college);
        Membership membership = memberships.saveAndFlush(Membership.request(student, college, "CS-004", "Mechanical"));

        membership.reject(tpo, "Not on the roll list", Instant.now());
        memberships.saveAndFlush(membership);

        Membership found = memberships.findById(membership.getId()).orElseThrow();
        assertThat(found.getVersion()).isEqualTo(1L);
        assertThat(found.getStatus()).isEqualTo(MembershipStatus.REJECTED);
        assertThat(found.getReason()).isEqualTo("Not on the roll list");
        assertThat(found.getDecidedBy()).isEqualTo(tpo);
        assertThat(found.getDecidedAt()).isNotNull();
    }

    @Test
    void findByIdAndCollegeId_otherCollege_empty() {
        UUID college = fixtures.college("Scoped");
        UUID otherCollege = fixtures.college("Other");
        Membership membership = memberships.saveAndFlush(
                Membership.request(fixtures.student(), college, "CS-005", "Civil"));

        assertThat(memberships.findByIdAndCollegeId(membership.getId(), college)).isPresent();
        assertThat(memberships.findByIdAndCollegeId(membership.getId(), otherCollege)).isEmpty();
    }

    @Test
    void findByUserId_returnsTheStudentsRow() {
        UUID student = fixtures.student();
        Membership membership = memberships.saveAndFlush(
                Membership.request(student, fixtures.college("ByUser"), "CS-006", "Civil"));

        assertThat(memberships.findByUserId(student)).map(Membership::getId).contains(membership.getId());
        assertThat(memberships.findByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findPending_ownCollegeOnly_oldestRequestFirst() {
        UUID college = fixtures.college("Queue");
        UUID tpo = fixtures.tpo(college);
        Membership first = memberships.saveAndFlush(Membership.request(fixtures.student(), college, "Q-1", "Civil"));
        Membership second = memberships.saveAndFlush(Membership.request(fixtures.student(), college, "Q-2", "Civil"));
        Membership decided = memberships.saveAndFlush(Membership.request(fixtures.student(), college, "Q-3", "Civil"));
        decided.approve(tpo, Instant.now());
        memberships.saveAndFlush(decided);
        memberships.saveAndFlush(Membership.request(fixtures.student(), fixtures.college("Elsewhere"), "Q-4", "Civil"));

        assertThat(memberships.findPending(college))
                .extracting(Membership::getId)
                .containsExactly(first.getId(), second.getId());
    }
}
