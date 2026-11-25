import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** PRD §6.4 / 02 §7: OPEN → CONFIRMED or REJECTED, decided once by the admin. */
export type ScamReportStatus = 'OPEN' | 'CONFIRMED' | 'REJECTED';

/** `ScamReportResponse`: the reporter's own view (never the evidence or the markers). */
export interface ScamReport {
  readonly id: string;
  readonly submissionId: string;
  readonly status: ScamReportStatus;
  readonly amountAsked: number | null;
  readonly createdAt: string;
  readonly decidedAt: string | null;
  /**
   * Only on the `POST` answer: the registry markers this report created or re-opened. 0 means
   * nothing about this letter can warn others. Missing in older answers.
   */
  readonly markersRecorded?: number;
}

export interface NewScamReport {
  readonly submissionId: string;
  /** What happened, at most 2,000 characters. */
  readonly description: string;
  /** Rupees asked for, 0–100,000,000, or null when nothing was asked. */
  readonly amountAsked: number | null;
}

/** "Report a scam" (PRD F6): one report per letter (409), at most 5 a day (429). */
@Service()
export class ScamReportApi {
  private readonly http = inject(HttpClient);

  /** The user's own reports, newest first. */
  mine(): Observable<ScamReport[]> {
    return this.http.get<ScamReport[]>('/api/scam-reports');
  }

  report(report: NewScamReport): Observable<ScamReport> {
    return this.http.post<ScamReport>('/api/scam-reports', report);
  }
}
