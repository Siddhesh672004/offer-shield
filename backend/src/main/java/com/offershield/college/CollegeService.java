package com.offershield.college;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    /** Only ACTIVE colleges accept join requests. */
    @Transactional(readOnly = true)
    public Optional<CollegeSummary> findActiveSummary(UUID collegeId) {
        return repository.findById(collegeId)
                .filter(college -> college.getStatus() == CollegeStatus.ACTIVE)
                .map(college -> new CollegeSummary(college.getId(), college.getName()));
    }

    /** ACTIVE colleges whose name contains {@code query} (case-insensitive), ordered by name, at most 20. */
    @Transactional(readOnly = true)
    public List<CollegeSearchItem> search(String query) {
        String text = query == null ? "" : query.trim();
        return repository.findTop20ByStatusAndNameContainingIgnoreCaseOrderByNameAsc(CollegeStatus.ACTIVE, text)
                .stream()
                .map(CollegeSearchItem::from)
                .toList();
    }
}
