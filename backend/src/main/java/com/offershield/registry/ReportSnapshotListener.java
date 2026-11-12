package com.offershield.registry;

import com.offershield.common.events.AnalysisCompletedEvent;
import com.offershield.common.events.Topics;
import com.offershield.common.kafka.InvalidEventException;
import com.offershield.common.logging.LogContext;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Consumer group {@code registry-snapshot} of {@code analysis.completed} (D-20 fan-out next to
 * {@code submission-status}; PRD §5.3: the registry gets report data by consuming the event, never by calling the
 * analysis). A record that is not valid JSON, or whose IDs, outcome, band, score or keys are not what the analysis
 * sends, goes to {@code analysis.completed.DLT} without retries ({@code KafkaConsumerConfig}); a database failure is
 * retried 3 times first. P13: an unknown sender source, a negative email-domain count or a marker hint that is not
 * masked is refused the same way, so a raw phone number or UPI ID can never be stored as a hint.
 */
@Component
public class ReportSnapshotListener {

    public static final String ID = ReportSnapshotService.CONSUMER;
    private static final Set<String> OUTCOMES = Set.of(AnalysisCompletedEvent.READY, AnalysisCompletedEvent.PARTIAL);
    private static final Set<String> BANDS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Pattern HMAC_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> SOURCES = Set.of("TYPED", "LETTER");
    private static final Set<String> HINT_TYPES = Set.of("PHONE", "UPI");
    /** "••••" and a phone's last 4 digits, or a UPI ID's last 0–2 handle characters and its provider. */
    private static final Pattern MASKED_HINT = Pattern.compile(
            "•{4}(?:\\d{4}|[a-z0-9._-]{0,2}@[a-z][a-z0-9]{1,34})");

    private final ReportSnapshotService snapshots;

    public ReportSnapshotListener(ReportSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @KafkaListener(id = ID, groupId = ID, topics = Topics.ANALYSIS_COMPLETED,
            properties = "spring.json.value.default.type=com.offershield.common.events.AnalysisCompletedEvent")
    public void onAnalysisCompleted(AnalysisCompletedEvent event) {
        if (event == null || event.eventId() == null || event.payload() == null
                || event.payload().submissionId() == null || event.payload().ownerUserId() == null) {
            throw new InvalidEventException("analysis.completed without event, submission or owner ID");
        }
        AnalysisCompletedEvent.Payload payload = event.payload();
        MDC.put(LogContext.USER_ID, payload.ownerUserId().toString());
        if (!OUTCOMES.contains(String.valueOf(payload.outcome())) || !BANDS.contains(String.valueOf(payload.band()))
                || payload.score() < 0 || payload.score() > 100) {
            throw new InvalidEventException("analysis.completed with an unknown outcome, band or score");
        }
        if (!wellFormed(payload.phoneHmacs()) || !wellFormed(payload.upiHmacs())) {
            throw new InvalidEventException("analysis.completed with a key that is not an HMAC");
        }
        if (!validP13Fields(payload)) {
            throw new InvalidEventException("analysis.completed with an unknown sender source or an unmasked hint");
        }
        snapshots.store(event.eventId(), payload, event.occurredAt() == null ? Instant.now() : event.occurredAt());
    }

    private static boolean wellFormed(List<String> keys) {
        return keys == null || keys.stream().allMatch(key -> key != null && HMAC_HEX.matcher(key).matches());
    }

    /** P13 (S2, N2): a known sender source, a non-negative domain count and only masked PHONE / UPI hints. */
    private static boolean validP13Fields(AnalysisCompletedEvent.Payload payload) {
        if (payload.senderSource() != null && !SOURCES.contains(payload.senderSource())) {
            return false;
        }
        if (payload.letterEmailDomains() != null && payload.letterEmailDomains() < 0) {
            return false;
        }
        return payload.markerHints().stream().allMatch(hint -> hint != null
                && HINT_TYPES.contains(String.valueOf(hint.type())) && SOURCES.contains(String.valueOf(hint.source()))
                && hint.key() != null && HMAC_HEX.matcher(hint.key()).matches()
                && (hint.hint() == null || MASKED_HINT.matcher(hint.hint()).matches()));
    }
}
