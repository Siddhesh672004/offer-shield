package com.offershield.college;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** The college feature's public face: other features call this service, never {@link CollegeRepository}. */
@Service
public class CollegeService {

    private final CollegeRepository repository;

    public CollegeService(CollegeRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<CollegeSummary> findSummary(UUID collegeId) {
        return repository.findById(collegeId).map(college -> new CollegeSummary(college.getId(), college.getName()));
    }
}
