package com.offershield.registry;

import com.offershield.common.audit.AuditLogService;
import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns {@code indicator} and {@code indicator_reporter}: the write side of the shared scam registry, called by the
 * scam-report and review features (P10) after their own checks (ownership, roles, If-Match on their decision, limits).
 * Markers always come from a report snapshot through {@link IndicatorFactory}, so free-mail and official company
 * domains never become markers (D-08), and phone numbers and UPI IDs are only HMAC keys. Every method runs in one
 * transaction and locks the marker rows it changes, in a fixed order, so parallel reports cannot lose a reporter.
 *
 * <ul>
 *   <li>{@link #reportScam}: a user's report (PRD §6.4 steps 2–3): markers created or re-opened as UNVERIFIED (a
 *       CONFIRMED marker stays CONFIRMED) and the user added once per submission; counts are distinct people
 *       (D-33.5).</li>
 *   <li>{@link #confirm}: the admin confirms a report, or a TPO decides CONFIRMED_SCAM (§6.4 step 6, §6.5): markers
 *       become CONFIRMED and force High from then on (C10/C11).</li>
 *   <li>{@link #withdrawReport}: the admin rejects a report (D-33.4): that reporter is removed from its markers, and an
 *       UNVERIFIED marker left without reporters becomes REJECTED (stops counting).</li>
 *   <li>{@link #revert}: the admin's Revert (D-13): CONFIRMED → UNVERIFIED (REJECTED if nobody reported it),
 *       guarded by the marker's version and written to the audit log.</li>
 * </ul>
 */
@Service
public class IndicatorService {

    static final String REVERTED = "INDICATOR_REVERTED";
    private static final Comparator<MarkerCandidate> LOCK_ORDER = Comparator
            .comparing(MarkerCandidate::type).thenComparing(MarkerCandidate::key);

    private final IndicatorRepository indicators;
    private final ReportSnapshotService snapshots;
    private final IndicatorFactory factory;
    private final AuditLogService audit;

    public IndicatorService(IndicatorRepository indicators, ReportSnapshotService snapshots, IndicatorFactory factory,
                            AuditLogService audit) {
        this.indicators = indicators;
        this.snapshots = snapshots;
        this.factory = factory;
        this.audit = audit;
    }

    /**
     * Records {@code reporterUserId}'s scam report of a submission on every marker of its snapshot.
     *
     * @return the markers after the change, in lock order
     * @throws NotFoundException when the submission has no snapshot (not analysed yet, or deleted)
     */
    @Transactional
    public List<IndicatorView> reportScam(UUID submissionId, UUID reporterUserId) {
        List<IndicatorView> result = new ArrayList<>();
        for (MarkerCandidate candidate : candidates(submissionId)) {
            IndicatorRow row = lockOrCreate(candidate, IndicatorStatus.UNVERIFIED);
            indicators.addReporter(row.id(), reporterUserId, submissionId);
            int reporters = indicators.countReportersLocked(row.id());
            IndicatorStatus status = row.status() == IndicatorStatus.CONFIRMED
                    ? IndicatorStatus.CONFIRMED : IndicatorStatus.UNVERIFIED;
            result.add(save(row, status, reporters, row.confirmedBy(), row.confirmedAt()));
        }
        return result;
    }

    /**
     * Confirms every marker of a submission's snapshot ({@code actorUserId} = the admin or TPO who decided).
     *
     * @return the markers after the change, in lock order
     * @throws NotFoundException when the submission has no snapshot
     */
    @Transactional
    public List<IndicatorView> confirm(UUID submissionId, UUID actorUserId) {
        List<IndicatorView> result = new ArrayList<>();
        for (MarkerCandidate candidate : candidates(submissionId)) {
            IndicatorRow row = lockOrCreate(candidate, IndicatorStatus.CONFIRMED);
            if (row.status() == IndicatorStatus.CONFIRMED && row.confirmedBy() != null) {
                result.add(row.view());
                continue;
            }
            int reporters = indicators.countReportersLocked(row.id());
            result.add(save(row, IndicatorStatus.CONFIRMED, reporters, actorUserId,
                    Instant.now().truncatedTo(ChronoUnit.MICROS)));
        }
        return result;
    }

    /**
     * D-33.4: removes {@code reporterUserId}'s report of {@code submissionId} from its markers. Works without the
     * snapshot (it may already be deleted). A CONFIRMED marker stays CONFIRMED.
     *
     * @return the markers that lost this reporter, after the change
     */
    @Transactional
    public List<IndicatorView> withdrawReport(UUID submissionId, UUID reporterUserId) {
        List<IndicatorRow> locked = new ArrayList<>();
        List<IndicatorRow> attached = indicators.indicatorsReportedBy(submissionId, reporterUserId).stream()
                .map(indicators::findById).flatMap(Optional::stream)
                .sorted(Comparator.comparing(IndicatorRow::type).thenComparing(IndicatorRow::key)).toList();
        for (IndicatorRow row : attached) {
            indicators.lockById(row.id()).ifPresent(locked::add); // same lock order as reportScam / confirm
        }
        indicators.removeReporter(submissionId, reporterUserId);
        List<IndicatorView> result = new ArrayList<>();
        for (IndicatorRow row : locked) {
            int reporters = indicators.countReportersLocked(row.id());
            IndicatorStatus status = row.status() == IndicatorStatus.UNVERIFIED && reporters == 0
                    ? IndicatorStatus.REJECTED : row.status();
            result.add(save(row, status, reporters, row.confirmedBy(), row.confirmedAt()));
        }
        return result;
    }

    /**
     * D-13 Revert: a CONFIRMED marker goes back to UNVERIFIED (to REJECTED when nobody reported it, since it would count
     * for nothing), and the action is audited ({@value #REVERTED}).
     *
     * @throws NotFoundException when there is no such marker
     * @throws ConflictException when {@code expectedVersion} is stale or the marker is not CONFIRMED
     */
    @Transactional
    public IndicatorView revert(UUID indicatorId, long expectedVersion, UUID actorUserId) {
        IndicatorRow row = indicators.lockById(indicatorId)
                .orElseThrow(() -> new NotFoundException("Scam marker not found"));
        if (row.version() != expectedVersion) {
            throw new ConflictException("This marker was changed by someone else. Reload and try again.");
        }
        if (row.status() != IndicatorStatus.CONFIRMED) {
            throw new ConflictException("NOT_CONFIRMED", "Only a confirmed scam marker can be reverted.");
        }
        int reporters = indicators.countReportersLocked(row.id());
        IndicatorView reverted = save(row, reporters > 0 ? IndicatorStatus.UNVERIFIED : IndicatorStatus.REJECTED,
                reporters, null, null);
        audit.record(actorUserId, REVERTED, indicatorId);
        return reverted;
    }

    /** The registry markers a submission's snapshot maps to that exist now (for the admin queue and review evidence). */
    @Transactional(readOnly = true)
    public List<IndicatorView> markersOf(UUID submissionId) {
        return existing(candidates(submissionId));
    }

    /**
     * {@link #markersOf(UUID)} for a snapshot the caller already read, as evidence: P13 (N2) adds each PHONE / UPI
     * marker's source (in the letter, or only typed by the student) and masked hint from the snapshot, so a reviewer
     * knows what they confirm without ever seeing the value.
     */
    @Transactional(readOnly = true)
    public List<IndicatorView> markersOf(ReportSnapshotView snapshot) {
        Map<String, AnalysisCompletedEvent.MarkerHint> hints = new HashMap<>();
        snapshot.markerHints().forEach(hint -> hints.putIfAbsent(hint.type() + ":" + hint.key(), hint));
        List<IndicatorView> views = new ArrayList<>();
        for (MarkerCandidate candidate : factory.candidates(snapshot).stream().sorted(LOCK_ORDER).toList()) {
            indicators.findByKey(candidate.type(), candidate.key()).map(IndicatorRow::view).ifPresent(view -> {
                AnalysisCompletedEvent.MarkerHint hint = hints.get(candidate.type().name() + ":" + candidate.key());
                views.add(hint == null ? view : view.withHint(hint.source(), hint.hint()));
            });
        }
        return views;
    }

    private List<IndicatorView> existing(List<MarkerCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> indicators.findByKey(candidate.type(), candidate.key()))
                .flatMap(Optional::stream).map(IndicatorRow::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<IndicatorView> find(UUID indicatorId) {
        return indicators.findById(indicatorId).map(IndicatorRow::view);
    }

    private List<MarkerCandidate> candidates(UUID submissionId) {
        ReportSnapshotView snapshot = snapshots.find(submissionId)
                .orElseThrow(() -> new NotFoundException("Report not found"));
        return factory.candidates(snapshot).stream().sorted(LOCK_ORDER).toList();
    }

    private IndicatorRow lockOrCreate(MarkerCandidate candidate, IndicatorStatus initial) {
        indicators.insertIfAbsent(candidate, initial);
        return indicators.lockByKey(candidate.type(), candidate.key())
                .orElseThrow(() -> new IllegalStateException("marker missing right after its insert"));
    }

    private IndicatorView save(IndicatorRow row, IndicatorStatus status, int reporters, UUID confirmedBy,
                               Instant confirmedAt) {
        UUID by = status == IndicatorStatus.CONFIRMED ? confirmedBy : null;
        Instant at = status == IndicatorStatus.CONFIRMED ? confirmedAt : null;
        indicators.update(row.id(), status, reporters, by, at);
        return new IndicatorView(row.id(), row.type(), status, row.type() == IndicatorType.DOMAIN ? row.key() : null,
                reporters, row.version() + 1, at);
    }
}
