import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** PRD F5 / 02 §7: OPEN → CONFIRMED_SCAM / CONFIRMED_GENUINE / INCONCLUSIVE, decided once. */
export type ReviewStatus = 'OPEN' | 'CONFIRMED_SCAM' | 'CONFIRMED_GENUINE' | 'INCONCLUSIVE';

/** `ReviewCaseResponse`: the student's own view; `note` is the TPO's note once decided. */
export interface ReviewCase {
  readonly id: string;
  readonly submissionId: string;
  readonly status: ReviewStatus;
  readonly note: string | null;
  readonly createdAt: string;
  readonly decidedAt: string | null;
}

/** "Ask my TPO" for the signed-in student (PRD F5, §6.5). */
@Service()
export class ReviewApi {
  private readonly http = inject(HttpClient);

  /** The student's own cases, newest first. */
  mine(): Observable<ReviewCase[]> {
    return this.http.get<ReviewCase[]>('/api/reviews');
  }

  /** 404 unless it is the student's own analysed on-campus letter; 409 `REVIEW_EXISTS`. */
  ask(submissionId: string): Observable<ReviewCase> {
    return this.http.post<ReviewCase>('/api/reviews', { submissionId });
  }
}
