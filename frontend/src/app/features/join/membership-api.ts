import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';
import { MembershipStatus } from '../../core/auth/session';

/** One row of `GET /api/colleges?q=` (ACTIVE colleges only, max 20, by name). */
export interface CollegeOption {
  readonly id: string;
  readonly name: string;
  readonly city: string | null;
}

/** `MembershipResponse` from the P3 contract. */
export interface Membership {
  readonly id: string;
  readonly collegeId: string;
  readonly collegeName: string;
  readonly rollNo: string;
  readonly department: string;
  readonly status: Exclude<MembershipStatus, 'NONE'>;
  readonly reason: string | null;
  readonly version: number;
}

export interface JoinRequest {
  readonly collegeId: string;
  readonly rollNo: string;
  readonly department: string;
}

/** Calls for the "Join my college" flow (manual TPO approval only, D-17). */
@Service()
export class MembershipApi {
  private readonly http = inject(HttpClient);

  searchColleges(query: string): Observable<CollegeOption[]> {
    const q = query.trim();
    return this.http.get<CollegeOption[]>(
      '/api/colleges',
      q ? { params: new HttpParams().set('q', q) } : {},
    );
  }

  /** The student's own request, or `null` when there is none (404). */
  myMembership(): Observable<Membership | null> {
    return this.http
      .get<Membership>('/api/memberships/me')
      .pipe(
        catchError((error: unknown) =>
          error instanceof HttpErrorResponse && error.status === 404
            ? of(null)
            : throwError(() => error),
        ),
      );
  }

  requestToJoin(request: JoinRequest): Observable<Membership> {
    return this.http.post<Membership>('/api/memberships', request);
  }
}
