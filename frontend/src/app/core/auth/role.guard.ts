import { inject } from '@angular/core';
import { CanMatchFn } from '@angular/router';
import { Role, Session } from './session';

/**
 * Matches the route only for the given role. A wrong role doesn't match, so the router falls
 * through to "Page not found". The server still answers 403 for the wrong role on every call.
 */
export function roleGuard(role: Role): CanMatchFn {
  return async () => {
    const session = inject(Session);
    const me = await session.load();
    return me?.role === role;
  };
}
