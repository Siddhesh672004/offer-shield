import { DOCUMENT, Service, inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { Session } from './session';

const STORAGE_KEY = 'offershield.own-path';

/**
 * Remembers, in this browser only, that a student chose "Continue on my own", so the
 * Choose-path screen isn't shown after every sign-in. Only the opaque user id is stored.
 * Blocked storage (private mode) falls back to memory for this visit.
 */
@Service()
export class PathChoice {
  private readonly document = inject(DOCUMENT);
  private readonly remembered = new Set<string>();

  chooseOwnPath(userId: string): void {
    this.remembered.add(userId);
    try {
      this.document.defaultView?.localStorage.setItem(STORAGE_KEY, userId);
    } catch {
      // Storage blocked: the in-memory copy is enough for this visit.
    }
  }

  hasChosenOwnPath(userId: string): boolean {
    if (this.remembered.has(userId)) {
      return true;
    }
    try {
      return this.document.defaultView?.localStorage.getItem(STORAGE_KEY) === userId;
    } catch {
      return false;
    }
  }
}

/**
 * After sign-in the server sends everyone to /app. A student who hasn't joined a college and
 * hasn't chosen to continue alone is sent to /start first ("Join my college" / "Continue on my own").
 */
export const choosePathGuard: CanMatchFn = async () => {
  const session = inject(Session);
  const choice = inject(PathChoice);
  const router = inject(Router);

  const me = await session.load();
  if (
    !me ||
    me.role !== 'USER' ||
    me.membershipStatus !== 'NONE' ||
    choice.hasChosenOwnPath(me.userId)
  ) {
    return true;
  }
  return router.createUrlTree(['/start']);
};
