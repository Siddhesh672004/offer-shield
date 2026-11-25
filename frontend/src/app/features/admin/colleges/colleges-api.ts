import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type CollegeStatus = 'ACTIVE' | 'INACTIVE';

/** `AdminCollegeResponse`: a college with its TPOs and the emails still waiting for a first sign-in. */
export interface AdminCollege {
  readonly id: string;
  readonly name: string;
  readonly city: string | null;
  readonly status: CollegeStatus;
  readonly tpoEmails: readonly string[];
  readonly pendingTpoEmails: readonly string[];
}

/** `CollegeResponse`: the college just created. */
export interface CreatedCollege {
  readonly id: string;
  readonly name: string;
  readonly city: string | null;
  readonly status: CollegeStatus;
  readonly version: number | null;
}

/** ASSIGNED: promoted now. PENDING: promoted at that email's first Google sign-in (Google-confirmed email). */
export type TpoAssignmentStatus = 'ASSIGNED' | 'PENDING';

export interface TpoAssignment {
  readonly email: string;
  readonly status: TpoAssignmentStatus;
}

/** The admin's colleges and TPO assignments (P3-01; ADMIN only on the server). */
@Service()
export class AdminCollegesApi {
  private readonly http = inject(HttpClient);

  /** Every college (any status), by name. */
  list(): Observable<AdminCollege[]> {
    return this.http.get<AdminCollege[]>('/api/admin/colleges');
  }

  /** 409 when the name is taken (ignoring case and accents). */
  create(name: string, city: string | null): Observable<CreatedCollege> {
    return this.http.post<CreatedCollege>('/api/admin/colleges', { name, city });
  }

  assignTpo(collegeId: string, email: string): Observable<TpoAssignment> {
    return this.http.post<TpoAssignment>(
      `/api/admin/colleges/${encodeURIComponent(collegeId)}/tpos`,
      { email },
    );
  }
}
