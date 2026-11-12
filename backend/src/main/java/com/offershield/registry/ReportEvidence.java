package com.offershield.registry;

import com.offershield.analysis.checks.CheckCode;
import com.offershield.analysis.scoring.Band;
import com.offershield.common.events.AnalysisCompletedEvent;

import java.time.Instant;
import java.util.List;

/**
 * What a TPO (review case) or the admin (scam-report queue) sees about a letter (D-04): the report snapshot's band
 * with its PRD §7.2 message, score, override and partial flags, the registrable sender domain, the signals with their
 * masked evidence, and the registry markers the letter maps to now (type, status, distinct reporter count, version for
 * Revert). Never the letter text, the HMAC keys or the SimHash, and never who reported.
 */
public record ReportEvidence(String band, String bandMessage, int score, boolean hardOverride, boolean partial,
                             String senderDomain, List<EvidenceSignal> signals, List<IndicatorView> markers,
                             Instant completedAt) {

    public ReportEvidence {
        signals = List.copyOf(signals);
        markers = List.copyOf(markers);
    }

    static ReportEvidence of(ReportSnapshotView snapshot, List<IndicatorView> markers) {
        return new ReportEvidence(snapshot.band(), bandMessage(snapshot.band()), snapshot.score(),
                snapshot.hardOverride(), snapshot.partial(), snapshot.domain(),
                snapshot.signals().stream().map(ReportEvidence::signal).toList(), markers, snapshot.completedAt());
    }

    private static EvidenceSignal signal(AnalysisCompletedEvent.Signal signal) {
        return new EvidenceSignal(signal.code(), title(signal.code()), signal.outcome(), signal.points(),
                signal.hardOverride(), signal.explanation(), signal.evidence(), signal.sourceKind(),
                signal.aiExtracted());
    }

    private static String title(String code) {
        try {
            return CheckCode.valueOf(code).title();
        } catch (IllegalArgumentException ex) {
            return code;
        }
    }

    private static String bandMessage(String band) {
        try {
            return Band.valueOf(band).message();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
