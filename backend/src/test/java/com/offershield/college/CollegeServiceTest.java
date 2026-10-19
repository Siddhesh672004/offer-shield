package com.offershield.college;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollegeServiceTest {

    @Mock
    private CollegeRepository repository;

    @InjectMocks
    private CollegeService service;

    @Test
    void findSummary_knownCollege_returnsIdAndName() {
        College college = College.create("Example Institute of Technology", "Pune");
        when(repository.findById(college.getId())).thenReturn(Optional.of(college));

        Optional<CollegeSummary> summary = service.findSummary(college.getId());

        assertThat(summary).contains(new CollegeSummary(college.getId(), "Example Institute of Technology"));
    }

    @Test
    void findSummary_unknownCollege_empty() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.findSummary(unknown)).isEmpty();
    }

    @Test
    void findActiveSummary_activeCollege_returnsSummary() {
        College college = College.create("Active College", "Nagpur");
        when(repository.findById(college.getId())).thenReturn(Optional.of(college));

        assertThat(service.findActiveSummary(college.getId())).contains(new CollegeSummary(college.getId(), "Active College"));
    }

    @Test
    void findActiveSummary_unknownCollege_empty() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.findActiveSummary(unknown)).isEmpty();
    }
}
