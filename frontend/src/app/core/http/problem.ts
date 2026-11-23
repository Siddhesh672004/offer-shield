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

/** One failed constraint of a 400 `VALIDATION_FAILED`: the field name and why (never the value). */
export interface Violation {
  readonly field: string;
  readonly message: string;
}

/** The `violations` of a 400 `VALIDATION_FAILED` problem; empty for any other error. */
export function problemViolations(error: unknown): Violation[] {
  const violations: unknown = (readProblem(error) as { violations?: unknown } | undefined)
    ?.violations;
  if (!Array.isArray(violations)) {
    return [];
  }
  return violations.filter(
    (item): item is Violation =>
      typeof item === 'object' &&
      item !== null &&
      typeof (item as Violation).field === 'string' &&
      typeof (item as Violation).message === 'string',
  );
}

/** The problem body of a failed API call, or `undefined` when the error carries no JSON body. */
export function readProblem(error: unknown): ProblemDetail | undefined {
  if (!(error instanceof HttpErrorResponse)) {
    return undefined;
  }
  const body: unknown = error.error;
  return typeof body === 'object' && body !== null ? (body as ProblemDetail) : undefined;
}

/**
 * A 401: the session ended. `unauthorizedInterceptor` already sends the user to
 * `/login?session=ended`, so pages show and announce nothing of their own for it.
 */
export function isSessionEnded(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 401;
}

/** The machine-readable `errorCode`, e.g. `MEMBERSHIP_EXISTS`. */
export function problemCode(error: unknown): string | undefined {
  const code = readProblem(error)?.errorCode;
  return typeof code === 'string' ? code : undefined;
}

/**
 * The `Retry-After` header of a 429 in whole seconds (the API sends seconds), or `null` when it
 * is missing or not a number.
 */
export function retryAfterSeconds(error: unknown): number | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const value = error.headers?.get('Retry-After')?.trim();
  if (!value || !/^\d+$/.test(value)) {
    return null;
  }
  return Number(value);
}

/** The server's `detail` (written to be shown to the user), or the given fallback. */
export function problemDetail(error: unknown, fallback: string): string {
  const detail = readProblem(error)?.detail;
  return typeof detail === 'string' && detail.trim() !== '' ? detail : fallback;
}
