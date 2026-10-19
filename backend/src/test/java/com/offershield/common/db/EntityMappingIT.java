package com.offershield.common.db;

import com.offershield.auth.AdminProperties;
import com.offershield.auth.AppUser;
import com.offershield.auth.AppUserRepository;
import com.offershield.auth.Role;
import com.offershield.auth.TpoAssignment;
import com.offershield.auth.TpoAssignmentRepository;
import com.offershield.college.College;
import com.offershield.college.CollegeRepository;
import com.offershield.college.CollegeService;
import com.offershield.college.CollegeStatus;
import com.offershield.college.CollegeSummary;
import com.offershield.common.audit.AuditLog;
import com.offershield.common.audit.AuditLogRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JPA entities match the Flyway schema on MySQL 8.4 ({@code ddl-auto=validate} would stop the context
 * otherwise) and that UUIDs are stored as BINARY(16).
 */
@TestPropertySource(properties = "offershield.admin.emails=Admin@Example.com, second.admin@example.com")
class EntityMappingIT extends IntegrationTest {

    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private CollegeService collegeService;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private TpoAssignmentRepository assignments;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private AdminProperties adminProperties;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void college_saved_readBackWithVersionAndBinaryId() {
        College saved = colleges.saveAndFlush(College.create("Mapping Test College", "Pune"));

        College found = colleges.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Mapping Test College");
        assertThat(found.getCity()).isEqualTo("Pune");
        assertThat(found.getStatus()).isEqualTo(CollegeStatus.ACTIVE);
        assertThat(found.getVersion()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT LOWER(HEX(id)) FROM college WHERE name = 'Mapping Test College'", String.class))
                .isEqualTo(saved.getId().toString().replace("-", ""));
        assertThat(collegeService.findSummary(saved.getId()))
                .contains(new CollegeSummary(saved.getId(), "Mapping Test College"));
    }

    @Test
    void appUser_saved_findByGoogleSubReturnsSameRow() {
        AppUser user = AppUser.create("sub-mapping-1", "student@example.com", true, "Test Student");
        users.saveAndFlush(user);

        AppUser found = users.findByGoogleSub("sub-mapping-1").orElseThrow();
        assertThat(found.getId()).isEqualTo(user.getId());
        assertThat(found.getEmail()).isEqualTo("student@example.com");
        assertThat(found.isEmailVerified()).isTrue();
        assertThat(found.getName()).isEqualTo("Test Student");
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.getTpoCollegeId()).isNull();
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE google_sub = 'sub-mapping-1'", String.class))
                .isEqualTo("USER");
        assertThat(users.findByGoogleSub("sub-missing")).isEmpty();
    }

    @Test
    void appUser_tpoWithCollege_persistsRoleAndCollege() {
        College college = colleges.saveAndFlush(College.create("TPO Mapping College", null));
        AppUser user = AppUser.create("sub-mapping-2", "tpo@example.com", true, null);
        user.assignTpoCollege(college.getId());
        user.changeRole(Role.TPO);
        users.saveAndFlush(user);

        AppUser found = users.findByGoogleSub("sub-mapping-2").orElseThrow();
        assertThat(found.getRole()).isEqualTo(Role.TPO);
        assertThat(found.getTpoCollegeId()).isEqualTo(college.getId());
        assertThat(found.getName()).isNull();
    }

    @Test
    void tpoAssignment_saved_findByEmailThenDelete() {
        College college = colleges.saveAndFlush(College.create("Assignment Mapping College", null));
        AppUser admin = users.saveAndFlush(AppUser.create("sub-mapping-3", "admin3@example.com", true, null));
        assignments.saveAndFlush(TpoAssignment.create("new.tpo@example.com", college.getId(), admin.getId()));

        TpoAssignment found = assignments.findByEmail("new.tpo@example.com").orElseThrow();
        assertThat(found.getCollegeId()).isEqualTo(college.getId());
        assertThat(found.getAssignedBy()).isEqualTo(admin.getId());

        assignments.delete(found);
        assignments.flush();
        assertThat(assignments.findByEmail("new.tpo@example.com")).isEmpty();
    }

    /** Spring Data skips delete() for an entity it thinks is new, so "new" must flip when persist() runs, not at flush. */
    @Test
    void entity_createdAndDeletedInOneTransaction_rowRemoved() {
        College college = transactionTemplate.execute(status -> {
            College created = colleges.save(College.create("Create Delete Same Tx College", null));
            colleges.delete(created);
            return created;
        });

        assertThat(colleges.findById(college.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM college WHERE name = 'Create Delete Same Tx College'",
                Integer.class)).isZero();
    }

    @Test
    void auditLog_saved_persistsActorActionAndTarget() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AuditLog saved = auditLogs.saveAndFlush(AuditLog.of(actor, "MAPPING_TEST", target));

        AuditLog found = auditLogs.findById(saved.getId()).orElseThrow();
        assertThat(found.getActorId()).isEqualTo(actor);
        assertThat(found.getAction()).isEqualTo("MAPPING_TEST");
        assertThat(found.getTargetId()).isEqualTo(target);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void adminProperties_boundFromConfiguration_matchesIgnoringCase() {
        assertThat(adminProperties.isAdmin("admin@example.com")).isTrue();
        assertThat(adminProperties.isAdmin(" SECOND.ADMIN@example.com ")).isTrue();
        assertThat(adminProperties.isAdmin("student@example.com")).isFalse();
    }
}
