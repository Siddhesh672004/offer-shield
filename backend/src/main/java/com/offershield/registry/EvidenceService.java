package com.offershield.registry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Builds the evidence a TPO or the admin sees (D-04) from the registry's report snapshot and the markers it maps to.
 * Callers do their own access check first (the TPO of the case's college, or the admin).
 */
@Service
public class EvidenceService {

    private final ReportSnapshotService snapshots;
    private final IndicatorService indicators;

    public EvidenceService(ReportSnapshotService snapshots, IndicatorService indicators) {
        this.snapshots = snapshots;
        this.indicators = indicators;
    }

    /** Empty when the submission has no snapshot (not analysed yet, or deleted by retention). */
    @Transactional(readOnly = true)
    public Optional<ReportEvidence> find(UUID submissionId) {
        return snapshots.find(submissionId)
                .map(snapshot -> ReportEvidence.of(snapshot, indicators.markersOf(snapshot)));
    }
}
