import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ReportSignal } from '../../shared/report/report-models';
import { RiskBand } from '../../shared/ui/risk-band';

/** Facts the AI read in the letter; any may be null. Always shown under "AI-extracted". */
export interface AiDetails {
  readonly label: string;
  readonly companyName: string | null;
  readonly recruiterName: string | null;
  readonly ctc: string | null;
  readonly joiningDate: string | null;
}

/**
 * The shared-registry outcome (C10–C12), so the two trust levels never mix:
 * `confirmedScam` → "Confirmed scam"; `reportedBy` N > 0 → "Reported as a scam by N users
 * (not verified)"; `wordingWarning` → "Wording resembles a reported scam" (a warning only).
 */
export interface RegistryOutcome {
  readonly confirmedScam: boolean;
  readonly wordingWarning: boolean;
  readonly reportedBy: number;
}

export type SenderSource = 'TYPED' | 'LETTER';

/** `GET /api/reports/{submissionId}` (owner only; 02 §5). */
export interface Report {
  readonly submissionId: string;
  readonly score: number;
  readonly band: RiskBand;
  readonly bandMessage: string;
  readonly hardOverride: boolean;
  readonly partial: boolean;
  /** false = "AI unavailable" (the report is then also partial). */
  readonly aiUsed: boolean;
  readonly aiDetails: AiDetails | null;
  readonly senderDomain: string | null;
  /**
   * Where `senderDomain` came from: the sender email the student typed, or the letter itself
   * (LETTER: say so under "Sender domain"). Missing in older answers.
   */
  readonly senderSource?: SenderSource | null;
  readonly unavailableChecks: readonly string[];
  readonly registry: RegistryOutcome;
  readonly signals: readonly ReportSignal[];
  readonly createdAt: string;
}

/** `POST /api/reports/{id}/explain`: plain text with line breaks; `aiWritten` false = template. */
export interface Explanation {
  readonly submissionId: string;
  readonly explanation: string;
  readonly aiWritten: boolean;
}

/** The owner's report and its "Explain simply" summary (PRD F4). */
@Service()
export class ReportApi {
  private readonly http = inject(HttpClient);

  get(submissionId: string): Observable<Report> {
    return this.http.get<Report>(`/api/reports/${encodeURIComponent(submissionId)}`);
  }

  explain(submissionId: string): Observable<Explanation> {
    return this.http.post<Explanation>(
      `/api/reports/${encodeURIComponent(submissionId)}/explain`,
      null,
    );
  }
}
