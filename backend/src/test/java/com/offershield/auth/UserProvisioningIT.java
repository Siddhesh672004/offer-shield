package com.offershield.auth;

import com.offershield.college.College;
import com.offershield.college.CollegeRepository;
import com.offershield.college.CollegeSummary;
import com.offershield.common.web.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provisioning and /me logic against real MySQL 8.4: transactions, FKs, audit row and assignment deletion. */
@SpringBootTest(properties = "offershield.admin.emails=it.admin@example.com")
@Testcontainers
class UserProvisioningIT {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private MeService meService;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private TpoAssignmentRepository assignments;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void provision_secondLoginWithNewEmail_updatesSameRow() {
        ProvisionedUser first = provisioning.provision(new OidcIdentity("it-sub-1", "first@example.com", true, "Test One"));
        ProvisionedUser second = provisioning.provision(new OidcIdentity("it-sub-1", "second@example.com", true, "Test Two"));

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(first.role()).isEqualTo(Role.USER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE google_sub = 'it-sub-1'", Integer.class)).isEqualTo(1);
        AppUser row = users.findByGoogleSub("it-sub-1").orElseThrow();
        assertThat(row.getEmail()).isEqualTo("second@example.com");
        assertThat(row.getName()).isEqualTo("Test Two");
    }

    @Test
    void provision_verifiedAdminEmailWithOddCase_admin() {
        ProvisionedUser admin = provisioning.provision(
                new OidcIdentity("it-sub-admin", " IT.Admin@Example.com ", true, "Test Admin"));

        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE google_sub = 'it-sub-admin'", String.class))
                .isEqualTo("ADMIN");
    }

    @Test
    void provision_unverifiedAdminEmail_staysUser() {
        ProvisionedUser result = provisioning.provision(
                new OidcIdentity("it-sub-unverified-admin", "it.admin@example.com", false, "Test Impostor"));

        assertThat(result.role()).isEqualTo(Role.USER);
    }

    @Test
    void provision_verifiedTpoAssignment_consumedWithAuditRowAndMeShowsCollege() {
        College college = colleges.saveAndFlush(College.create("Provisioning IT College", "Pune"));
        AppUser assigner = users.saveAndFlush(AppUser.create("it-sub-assigner", "assigner@example.com", true, null));
        assignments.saveAndFlush(TpoAssignment.create("it.tpo@example.com", college.getId(), assigner.getId()));

        ProvisionedUser tpo = provisioning.provision(new OidcIdentity("it-sub-tpo", "It.TPO@example.com", true, "Test TPO"));

        assertThat(tpo.role()).isEqualTo(Role.TPO);
        assertThat(assignments.findByEmail("it.tpo@example.com")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TPO_ASSIGNMENT_CONSUMED' AND actor_id = ? AND target_id = ?",
                Integer.class, uuidBytes(tpo.userId()), uuidBytes(college.getId()))).isEqualTo(1);
        MeResponse me = meService.me(tpo.userId());
        assertThat(me.role()).isEqualTo(Role.TPO);
        assertThat(me.college()).isEqualTo(new CollegeSummary(college.getId(), "Provisioning IT College"));
        assertThat(me.membershipStatus()).isEqualTo("NONE");

        ProvisionedUser again = provisioning.provision(new OidcIdentity("it-sub-tpo", "it.tpo@example.com", true, "Test TPO"));
        assertThat(again.role()).isEqualTo(Role.TPO);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id = ?", Integer.class,
                uuidBytes(tpo.userId()))).isEqualTo(1);
    }

    @Test
    void provision_unverifiedEmailWithAssignment_staysUserAndAssignmentRemains() {
        College college = colleges.saveAndFlush(College.create("Unverified IT College", null));
        AppUser assigner = users.saveAndFlush(AppUser.create("it-sub-assigner-2", "assigner2@example.com", true, null));
        assignments.saveAndFlush(TpoAssignment.create("it.unverified@example.com", college.getId(), assigner.getId()));

        ProvisionedUser result = provisioning.provision(
                new OidcIdentity("it-sub-unverified", "it.unverified@example.com", false, "Test Impostor"));

        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(assignments.findByEmail("it.unverified@example.com")).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id = ?", Integer.class,
                uuidBytes(result.userId()))).isZero();
    }

    @Test
    void me_unknownUser_notFound() {
        assertThatThrownBy(() -> meService.me(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
