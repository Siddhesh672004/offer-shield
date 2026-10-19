package com.offershield.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDirectoryTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final UserDirectory directory = new UserDirectory(repository);

    @Test
    void findContact_existingUser_returnsContact() {
        AppUser user = AppUser.create("sub-1", "student@example.com", true, "Asha Student");
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(directory.findContact(user.getId()))
                .contains(new UserContact(user.getId(), "student@example.com", "Asha Student", true));
    }

    @Test
    void findContact_unknownUser_empty() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(directory.findContact(id)).isEmpty();
    }

    @Test
    void findTpoCollegeId_tpo_returnsCollege() {
        AppUser tpo = AppUser.create("sub-2", "tpo@example.com", true, "Tara TPO");
        UUID collegeId = UUID.randomUUID();
        tpo.assignTpoCollege(collegeId);
        tpo.changeRole(Role.TPO);
        when(repository.findById(tpo.getId())).thenReturn(Optional.of(tpo));

        assertThat(directory.findTpoCollegeId(tpo.getId())).contains(collegeId);
    }

    @Test
    void findTpoCollegeId_plainUser_empty() {
        AppUser user = AppUser.create("sub-3", "user@example.com", true, "Uma User");
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(directory.findTpoCollegeId(user.getId())).isEmpty();
    }
}
