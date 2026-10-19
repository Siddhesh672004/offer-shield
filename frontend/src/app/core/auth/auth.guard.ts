import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { Session } from './session';

/**
 * Lets a signed-in user in; everyone else goes to /login. A failed Google sign-in lands on
 * `/app?login=failed`, so that flag is carried over for the login page to explain. When the
 * server can't be reached (network error, 5xx) the login page says so and keeps the page the
 * user asked for in `next`, so "Try again" can open it.
 * This only picks screens: the server still checks every `/api` call.
 */
export const authGuard: CanMatchFn = async (_route, segments, current) => {
  const session = inject(Session);
  const router = inject(Router);

  if (await session.load()) {
    return true;
  }
  if (session.status() === 'unreachable') {
    const target = router.currentNavigation()?.initialUrl;
    const next = target
      ? router.serializeUrl(target)
      : `/${segments.map((segment) => segment.path).join('/')}`;
    return router.createUrlTree(['/login'], { queryParams: { unreachable: 1, next } });
  }
  const loginFailed = current.queryParams['login'] === 'failed';
  return router.createUrlTree(['/login'], loginFailed ? { queryParams: { login: 'failed' } } : {});
};
