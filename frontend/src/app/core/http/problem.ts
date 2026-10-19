import { HttpErrorResponse } from '@angular/common/http';

/** An RFC 9457 problem body as the OfferShield API sends it (plus our `errorCode` and `requestId`). */
export interface ProblemDetail {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly errorCode?: string;
  readonly requestId?: string;
}

/** The problem body of a failed API call, or `undefined` when the error carries no JSON body. */
export function readProblem(error: unknown): ProblemDetail | undefined {
  if (!(error instanceof HttpErrorResponse)) {
    return undefined;
  }
  const body: unknown = error.error;
  return typeof body === 'object' && body !== null ? (body as ProblemDetail) : undefined;
}

/** The machine-readable `errorCode`, e.g. `MEMBERSHIP_EXISTS`. */
export function problemCode(error: unknown): string | undefined {
  const code = readProblem(error)?.errorCode;
  return typeof code === 'string' ? code : undefined;
}

/** The server's `detail` (written to be safe to show), or the given fallback. */
export function problemDetail(error: unknown, fallback: string): string {
  const detail = readProblem(error)?.detail;
  return typeof detail === 'string' && detail.trim() !== '' ? detail : fallback;
}
