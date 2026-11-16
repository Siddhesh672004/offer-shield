package com.offershield.scamreport;

import com.offershield.analysis.ReportService;
import com.offershield.auth.UserDirectory;
import com.offershield.common.Ids;
import com.offershield.common.audit.AuditLogService;
import com.offershield.common.crypto.SealedText;
import com.offershield.common.crypto.TextCipher;
import com.offershield.registry.EvidenceService;
import com.offershield.registry.IndicatorService;
import com.offershield.registry.ReportSnapshotService;
import com.offershield.submission.PdfLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D-06 (P11): 30 days after the admin's decision the retention job wipes a scam report's encrypted description (and
 * usually its snapshot). The admin queue then shows the decided report without a description or evidence instead of
 * failing to decrypt.
 */
class WipedDescriptionTest {

    private final ScamReportRepository repository = mock(ScamReportRepository.class);
    private final TextCipher textCipher = mock(TextCipher.class);
    private final EvidenceService evidence = mock(EvidenceService.class);
    private final ScamReportService service = new ScamReportService(repository, mock(ReportSnapshotService.class),
            mock(ReportService.class), mock(IndicatorService.class), evidence, mock(PdfLinkService.class), textCipher,
            mock(UserDirectory.class), mock(AuditLogService.class), mock(ApplicationEventPublisher.class));

    @Test
    void adminList_wipedDescription_shownAsNull() {
        ScamReport report = ScamReport.open(Ids.newId(), Ids.newId(), Ids.newId(), new SealedText(new byte[40], 1), 500);
        report.reject(Ids.newId(), Instant.now());
        // As loaded from the database after the retention job wiped the description.
        ReflectionTestUtils.setField(report, "version", 1L);
        ReflectionTestUtils.setField(report, "descriptionCipher", null);
        when(repository.findByStatusOrderByDecidedAtDescIdDesc(eq(ScamReportStatus.REJECTED), any()))
                .thenReturn(List.of(report));
        when(evidence.find(any())).thenReturn(Optional.empty());

        List<AdminScamReportResponse> list = service.list(ScamReportStatus.REJECTED);

        assertThat(list).singleElement().satisfies(response -> {
            assertThat(response.description()).isNull();
            assertThat(response.evidence()).isNull();
            assertThat(response.amountAsked()).isEqualTo(500);
        });
        verifyNoInteractions(textCipher);
    }
}
