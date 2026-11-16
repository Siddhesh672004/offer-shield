package com.offershield.scamreport;

import com.offershield.analysis.ReportService;
import com.offershield.auth.UserDirectory;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.crypto.SealedText;
import com.offershield.common.crypto.TextCipher;
import com.offershield.registry.EvidenceService;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.registry.ReportSnapshotView;
import com.offershield.submission.PdfLinkService;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P13 (silent failure 18): when the reporter's contact can't be found, the decision still succeeds and the skipped
 * email is logged (IDs only, never an address), instead of vanishing without a trace. Mocks only; fake IDs.
 */
class ScamReportEmailSkipTest {

    private final ScamReportRepository reports = mock(ScamReportRepository.class);
    private final ReportSnapshotService snapshots = mock(ReportSnapshotService.class);
    private final IndicatorService indicators = mock(IndicatorService.class);
    private final EvidenceService evidence = mock(EvidenceService.class);
    private final PdfLinkService pdfLinks = mock(PdfLinkService.class);
    private final TextCipher textCipher = mock(TextCipher.class);
    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ScamReportService service = new ScamReportService(reports, snapshots, mock(ReportService.class),
            indicators, evidence, pdfLinks, textCipher, userDirectory, mock(AuditLogService.class), events);

    @Test
    void decide_reporterContactMissing_emailSkippedAndLogged() {
        UUID reportId = UUID.randomUUID();
        UUID submission = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        ScamReport report = ScamReport.open(reportId, submission, reporter, new SealedText(new byte[16], 1), null);
        ReflectionTestUtils.setField(report, "version", 0L);
        when(reports.lockById(reportId)).thenReturn(Optional.of(report));
        when(snapshots.find(submission)).thenReturn(Optional.of(new ReportSnapshotView(submission, reporter, null,
                "HIGH", 30, true, false, null, List.of(), List.of(), null, List.of(), true, Instant.now())));
        when(indicators.confirm(any(), any())).thenReturn(List.of());
        when(userDirectory.findContact(reporter)).thenReturn(Optional.empty());
        when(evidence.find(any())).thenReturn(Optional.empty());
        when(textCipher.decrypt(any(), any())).thenReturn("Asked for a deposit");

        try (LogCapture logs = LogCapture.of(ScamReportService.class)) {
            AdminScamReportResponse decided = service.decide(UUID.randomUUID(), reportId, 0,
                    ScamReportDecision.CONFIRM);

            assertThat(decided.status()).isEqualTo(ScamReportStatus.CONFIRMED);
            assertThat(logs.events()).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("decision email skipped").contains("no contact"));
        }
        verifyNoInteractions(events);
    }
}
