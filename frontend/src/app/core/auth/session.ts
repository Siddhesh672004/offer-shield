import { HttpClient, HttpContext, HttpContextToken, HttpErrorResponse } from '@angular/common/http';
import { Service, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export type Role = 'USER' | 'TPO' | 'ADMIN';
export type MembershipStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED';

export interface CollegeRef {
  readonly id: string;
  readonly name: string;
}

/** Body of `GET /api/auth/me`. */
export interface Me {
  readonly userId: string;
  readonly name: string;
  readonly email: string;
  readonly role: Role;
  readonly college: CollegeRef | null;
  readonly membershipStatus: MembershipStatus;
}

/** `unreachable`: the server didn't answer (network error, 5xx); the next `load()` asks again. */
export type SessionStatus = 'unknown' | 'signed-in' | 'signed-out' | 'unreachable';

/** Marks the `/api/auth/me` probe, so a 401 there means "signed out" rather than "send to /login". */
export const SESSION_PROBE = new HttpContextToken<boolean>(() => false);

/**
 * Who is signed in, as told by the server. The browser never decides identity or role: this is
 * only a copy of `GET /api/auth/me` for showing the right screens. The server checks every call.
 */
@Service()
export class Session {
  private readonly http = inject(HttpClient);
  private readonly meState = signal<Me | null>(null);
  private readonly statusState = signal<SessionStatus>('unknown');
  private pending: Promise<Me | null> | null = null;

  readonly me = this.meState.asReadonly();
  readonly status = this.statusState.asReadonly();
  readonly signedIn = computed(() => this.statusState() === 'signed-in');

  /** Loads the session once; later calls reuse the answer. */
  load(): Promise<Me | null> {
    this.pending ??= this.fetch();
    return this.pending;
  }

  /** Asks the server again, e.g. after the membership status changed. */
  refresh(): Promise<Me | null> {
    this.pending = this.fetch();
    return this.pending;
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post<void>('/api/auth/logout', null));
    } catch (error) {
      if (!(error instanceof HttpErrorResponse && error.status === 401)) {
        throw error;
      }
    }
    this.markSignedOut();
  }

  markSignedOut(): void {
    this.meState.set(null);
    this.statusState.set('signed-out');
    this.pending = Promise.resolve(null);
  }

  private async fetch(): Promise<Me | null> {
    try {
      const me = await firstValueFrom(
        this.http.get<Me>('/api/auth/me', { context: new HttpContext().set(SESSION_PROBE, true) }),
      );
      this.meState.set(me);
      this.statusState.set('signed-in');
      return me;
    } catch (error) {
      this.meState.set(null);
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.statusState.set('signed-out');
      } else {
        this.statusState.set('unreachable');
        this.pending = null;
      }
      return null;
    }
  }
}
