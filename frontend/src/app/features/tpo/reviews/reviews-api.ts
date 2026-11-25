import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ifMatch } from '../../../core/http/if-match';
import { PdfLink, ReportEvidence } from '../../../shared/report/evidence-models';
import type { ReviewStatus } from '../../student/review-api';

export type { ReviewStatus };

/** A TPO's decision on an OPEN case (PRD F5); each one is final. */
export type ReviewDecision = Exclude<ReviewStatus, 'OPEN'>;

/**
 * `ReviewCaseDetail`: a case as the TPO of its college sees it (D-04): the student, the state,
 * the report snapshot's evidence (`null` once deleted after 30 days), whether a PDF link can be
 * made (D-05) and the version to send back as `If-Match`. Never the letter text.
 */
export interface ReviewCaseDetail {
  readonly id: string;
  readonly submissionId: string;
  readonly status: ReviewStatus;
  readonly note: string | null;
  readonly studentName: string | null;
  readonly studentEmail: string | null;
  readonly createdAt: string;
  readonly decidedAt: string | null;
  readonly version: number;
  readonly pdfAvailable: boolean;
  readonly evidence: ReportEvidence | null;
  /** Only on the decision answer: markers CONFIRMED_SCAM confirmed (0 otherwise; missing in older answers). */
  readonly markersRecorded?: number;
}

/** The TPO's review queue (PRD F5, §6.5). The college comes from the session; others are 404. */
@Service()
export class ReviewQueueApi {
  private readonly http = inject(HttpClient);

  /** The college's OPEN cases, oldest first (at most 100). */
  queue(): Observable<ReviewCaseDetail[]> {
    return this.http.get<ReviewCaseDetail[]>('/api/reviews/queue');
  }

  get(id: string): Observable<ReviewCaseDetail> {
    return this.http.get<ReviewCaseDetail>(`/api/reviews/${encodeURIComponent(id)}`);
  }

  /** 409 when stale (`CONFLICT`), decided (`ALREADY_DECIDED`) or the evidence is gone (`EVIDENCE_DELETED`). */
  decide(
    reviewCase: ReviewCaseDetail,
    decision: ReviewDecision,
    note: string | null,
  ): Observable<ReviewCaseDetail> {
    return this.http.post<ReviewCaseDetail>(
      `/api/reviews/${encodeURIComponent(reviewCase.id)}/decision`,
      { decision, note },
      { headers: ifMatch(reviewCase.version) },
    );
  }

  /** D-05: a 5-minute link; 404 `PDF_NOT_AVAILABLE` when no PDF is stored. */
  pdfLink(id: string): Observable<PdfLink> {
    return this.http.get<PdfLink>(`/api/reviews/${encodeURIComponent(id)}/pdf-link`);
  }
}
