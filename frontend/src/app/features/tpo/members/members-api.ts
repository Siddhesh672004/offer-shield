import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ifMatch } from '../../../core/http/if-match';

/** `PendingMembershipResponse`: one request in the TPO's own college queue, oldest first. */
export interface PendingMember {
  readonly id: string;
  readonly studentName: string;
  readonly studentEmail: string;
  readonly rollNo: string;
  readonly department: string;
  /** When the current request was made (a re-apply resets it). */
  readonly requestedAt: string;
  /** Sent back as `If-Match` with the decision. */
  readonly version: number;
}

/** The TPO's "Pending members" calls (PRD F2, D-17). The college comes from the session. */
@Service()
export class PendingMembersApi {
  private readonly http = inject(HttpClient);

  pending(): Observable<PendingMember[]> {
    return this.http.get<PendingMember[]>('/api/memberships/pending');
  }

  approve(member: PendingMember): Observable<unknown> {
    return this.decide(member, { decision: 'APPROVE' });
  }

  /** The reason is required; the student sees it. */
  reject(member: PendingMember, reason: string): Observable<unknown> {
    return this.decide(member, { decision: 'REJECT', reason });
  }

  private decide(member: PendingMember, body: object): Observable<unknown> {
    return this.http.post(`/api/memberships/${encodeURIComponent(member.id)}/decision`, body, {
      headers: ifMatch(member.version),
    });
  }
}
