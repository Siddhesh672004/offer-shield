import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import {
  EMPTY,
  Observable,
  catchError,
  concatWith,
  defer,
  exhaustMap,
  takeUntil,
  takeWhile,
  tap,
  throwError,
  timer,
} from 'rxjs';

/** Lifecycle (02 §7): QUEUED → READY / PARTIAL, QUEUED → FAILED (sweeper cap), any → DELETED (day 30). */
export type SubmissionStatus = 'QUEUED' | 'READY' | 'PARTIAL' | 'FAILED' | 'DELETED';
export type SourceType = 'PDF' | 'TEXT';

/** `SubmissionResponse`: the owner's view of one letter. Never the letter or contact details. */
export interface Submission {
  readonly id: string;
  readonly status: SubmissionStatus;
  readonly sourceType: SourceType;
  /** Made while the owner was an approved member of a college ("Ask my TPO" can apply). */
  readonly onCampus: boolean;
  readonly pdfStored: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
  /** When the 30-day retention job deletes it (D-06). */
  readonly expiresAt: string;
}

/** Body of `202 Accepted`; a replayed Idempotency-Key returns the same ID. */
export interface SubmissionAccepted {
  readonly submissionId: string;
}

/** A PDF `file` or pasted `text` (exactly one), plus optional details (blank ones are left out). */
export interface NewSubmission {
  readonly file: File | null;
  readonly text: string | null;
  readonly senderEmail: string;
  readonly recruiterPhone: string;
  readonly upiId: string;
}

/** D-14: check every 2 s, waiting for the previous answer, and give up after 3 minutes. */
export const POLL_INTERVAL_MS = 2_000;
export const POLL_LIMIT_MS = 3 * 60_000;
/** This many transient errors in a row mean OfferShield can't be reached. */
export const POLL_MAX_TRANSIENT_ERRORS = 5;

/**
 * Polling ended without reaching OfferShield: 5 transient errors in a row, or no answer at all
 * within the 3-minute cap. Not "Still processing": nothing says the letter is still queued.
 */
export class OfferShieldUnreachableError extends Error {
  override readonly name = 'OfferShieldUnreachableError';

  constructor() {
    super("Couldn't reach OfferShield.");
  }
}

/** The analysis has finished (successfully or not), so polling stops. */
export function isSettled(status: SubmissionStatus): boolean {
  return status !== 'QUEUED';
}

/** A hiccup worth one more try on the next tick: offline, a gateway error, or a rate limit. */
function isTransient(error: unknown): boolean {
  return error instanceof HttpErrorResponse && [0, 429, 502, 503, 504].includes(error.status);
}

/** Upload, list and status calls for the student's own letters (PRD §4 F3, §11). */
@Service()
export class SubmissionApi {
  private readonly http = inject(HttpClient);

  submit(letter: NewSubmission, idempotencyKey: string): Observable<SubmissionAccepted> {
    const body = new FormData();
    if (letter.file) {
      body.append('file', letter.file, letter.file.name);
    } else if (letter.text !== null) {
      body.append('text', letter.text);
    }
    appendIfFilled(body, 'senderEmail', letter.senderEmail);
    appendIfFilled(body, 'recruiterPhone', letter.recruiterPhone);
    appendIfFilled(body, 'upiId', letter.upiId);
    return this.http.post<SubmissionAccepted>('/api/submissions', body, {
      headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
    });
  }

  /** The owner's latest letters, newest first (deleted ones are left out by the API). */
  list(): Observable<Submission[]> {
    return this.http.get<Submission[]>('/api/submissions');
  }

  get(id: string): Observable<Submission> {
    return this.http.get<Submission>(`/api/submissions/${encodeURIComponent(id)}`);
  }

  /**
   * D-14 polling: asks every 2 s, but never while the previous answer is still on its way
   * (`exhaustMap` drops those ticks), emits each answer, and completes after the first settled
   * status or after 3 minutes, whichever comes first. A transient error skips one tick, but 5 in
   * a row, or no answer at all by the 3-minute cap, end the stream with
   * `OfferShieldUnreachableError`. Any other error (404, 401, 500) ends it with that error.
   */
  watch(id: string): Observable<Submission> {
    return defer(() => {
      let transientInARow = 0;
      let answered = false;
      return timer(0, POLL_INTERVAL_MS).pipe(
        exhaustMap(() =>
          this.get(id).pipe(
            tap(() => {
              transientInARow = 0;
              answered = true;
            }),
            catchError((error: unknown) => {
              if (!isTransient(error)) {
                return throwError(() => error);
              }
              transientInARow += 1;
              return transientInARow >= POLL_MAX_TRANSIENT_ERRORS
                ? throwError(() => new OfferShieldUnreachableError())
                : EMPTY;
            }),
          ),
        ),
        takeWhile((submission) => !isSettled(submission.status), true),
        takeUntil(timer(POLL_LIMIT_MS)),
        concatWith(
          defer(() => (answered ? EMPTY : throwError(() => new OfferShieldUnreachableError()))),
        ),
      );
    });
  }
}

function appendIfFilled(body: FormData, name: string, value: string): void {
  const trimmed = value.trim();
  if (trimmed !== '') {
    body.append(name, trimmed);
  }
}

/**
 * A fresh `Idempotency-Key` (the API accepts 8–100 of `A-Za-z0-9_.:-`). `crypto.randomUUID`
 * needs a secure context, which both https and http://localhost are; the fallback covers others.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}
