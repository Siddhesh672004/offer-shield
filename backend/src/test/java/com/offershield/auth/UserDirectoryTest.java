package com.offershield.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    void findContacts_manyUsers_oneRepositoryCallKeyedById() {
        AppUser first = AppUser.create("sub-4", "first@example.com", true, "First Student");
        AppUser second = AppUser.create("sub-5", "second@example.com", false, "Second Student");
        UUID unknown = UUID.randomUUID();
        when(repository.findAllById(any())).thenReturn(List.of(first, second));

        Map<UUID, UserContact> contacts = directory.findContacts(
                List.of(first.getId(), second.getId(), unknown, first.getId()));

        assertThat(contacts).containsOnlyKeys(first.getId(), second.getId());
        assertThat(contacts.get(second.getId()))
                .isEqualTo(new UserContact(second.getId(), "second@example.com", "Second Student", false));
        verify(repository, times(1)).findAllById(Set.of(first.getId(), second.getId(), unknown));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void findContacts_noIds_noQuery() {
        assertThat(directory.findContacts(List.of())).isEmpty();

        verifyNoInteractions(repository);
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
