import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { Session } from './session';

/**
 * Lets a signed-in user in; everyone else goes to /login. A failed Google sign-in lands on
 * `/app?login=failed`, so that flag is carried over for the login page to explain.
 * This only picks screens: the server still checks every `/api` call.
 */
export const authGuard: CanMatchFn = async (_route, _segments, current) => {
  const session = inject(Session);
  const router = inject(Router);

  if (await session.load()) {
    return true;
  }
  const loginFailed = current.queryParams['login'] === 'failed';
  return router.createUrlTree(['/login'], loginFailed ? { queryParams: { login: 'failed' } } : {});
};
