package com.offershield.auth;

import com.offershield.college.College;
import com.offershield.college.CollegeRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app_user} has no version column, so a sign-in that read the row before an admin's promotion committed must
 * not write the old role / college back: its UPDATE may only touch the columns it actually changed.
 */
class AppUserConcurrentUpdateIT extends IntegrationTest {

    @Autowired
    private AppUserRepository users;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void staleSignInUpdate_promotionCommittedMeanwhile_promotionKept() {
        String key = UUID.randomUUID().toString();
        UUID collegeId = colleges.saveAndFlush(College.create("Lost Update College " + key, null)).getId();
        UUID userId = users.saveAndFlush(AppUser.create("sub-lost-update-" + key, "before." + key + "@example.com", true,
                "Before Name")).getId();
        TransactionTemplate signIn = new TransactionTemplate(transactionManager);
        TransactionTemplate promotion = new TransactionTemplate(transactionManager);
        promotion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        signIn.executeWithoutResult(status -> {
            AppUser stale = users.findById(userId).orElseThrow();
            assertThat(stale.getRole()).isEqualTo(Role.USER);

            // Meanwhile, in its own transaction, an admin's promotion commits.
            promotion.executeWithoutResult(inner -> {
                AppUser fresh = users.findById(userId).orElseThrow();
                fresh.assignTpoCollege(collegeId);
                fresh.changeRole(Role.TPO);
            });

            stale.updateIdentity("after." + key + "@example.com", true, "After Name");
        });

        AppUser saved = users.findById(userId).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.TPO);
        assertThat(saved.getTpoCollegeId()).isEqualTo(collegeId);
        assertThat(saved.getEmail()).isEqualTo("after." + key + "@example.com");
        assertThat(saved.getName()).isEqualTo("After Name");
    }
}
