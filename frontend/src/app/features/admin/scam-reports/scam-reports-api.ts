import { HttpClient, HttpParams } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ifMatch } from '../../../core/http/if-match';
import { Marker, PdfLink, ReportEvidence } from '../../../shared/report/evidence-models';
import type { ScamReportStatus } from '../../student/scam-report-api';

export type { ScamReportStatus };

export type ScamReportDecision = 'CONFIRM' | 'REJECT';

/**
 * `AdminScamReportResponse` (PRD §6.4, D-04): the reporter's description (`null` once wiped 30
 * days after the decision) and amount, the report snapshot's evidence with the markers' status
 * and distinct reporter counts (`null` once deleted), whether a PDF link can be made (D-05) and
 * the version for `If-Match`. Never the letter text or who reported.
 */
export interface AdminScamReport {
  readonly id: string;
  readonly submissionId: string;
  readonly status: ScamReportStatus;
  readonly description: string | null;
  readonly amountAsked: number | null;
  readonly createdAt: string;
  readonly decidedAt: string | null;
  readonly version: number;
  readonly pdfAvailable: boolean;
  readonly evidence: ReportEvidence | null;
  /** Only on the decision answer: markers a CONFIRM confirmed (0 otherwise; missing in older answers). */
  readonly markersRecorded?: number;
}

/** The admin's scam-report moderation and Revert (PRD §6.4, D-05, D-13; ADMIN only). */
@Service()
export class AdminScamReportsApi {
  private readonly http = inject(HttpClient);

  /** OPEN oldest first; decided ones most recently decided first (at most 100). */
  list(status: ScamReportStatus): Observable<AdminScamReport[]> {
    return this.http.get<AdminScamReport[]>('/api/admin/scam-reports', {
      params: new HttpParams().set('status', status),
    });
  }

  /** 409 when stale (`CONFLICT`), decided (`ALREADY_DECIDED`) or the evidence is gone (`EVIDENCE_DELETED`). */
  decide(report: AdminScamReport, decision: ScamReportDecision): Observable<AdminScamReport> {
    return this.http.post<AdminScamReport>(
      `/api/admin/scam-reports/${encodeURIComponent(report.id)}/decision`,
      { decision },
      { headers: ifMatch(report.version) },
    );
  }

  /** D-05: a 5-minute link; 404 `PDF_NOT_AVAILABLE` when no PDF is stored. */
  pdfLink(reportId: string): Observable<PdfLink> {
    return this.http.get<PdfLink>(
      `/api/admin/scam-reports/${encodeURIComponent(reportId)}/pdf-link`,
    );
  }

  /** D-13: CONFIRMED → back to unconfirmed. 409 when stale (`CONFLICT`) or not confirmed (`NOT_CONFIRMED`). */
  revert(marker: Marker): Observable<Marker> {
    return this.http.post<Marker>(
      `/api/admin/indicators/${encodeURIComponent(marker.id)}/revert`,
      null,
      { headers: ifMatch(marker.version) },
    );
  }
}
