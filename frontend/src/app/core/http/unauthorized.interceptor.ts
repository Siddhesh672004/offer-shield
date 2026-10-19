import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { Session, SESSION_PROBE } from '../auth/session';

/**
 * The app's only interceptor: a 401 from `/api/**` means the session ended, so go to /login.
 * The `/api/auth/me` probe is skipped (a 401 there just means "signed out"). The error is
 * re-thrown so the caller can stop its spinner.
 */
export const unauthorizedInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  const session = inject(Session);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        isApiUrl(request.url) &&
        !request.context.get(SESSION_PROBE)
      ) {
        session.markSignedOut();
        void router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};

function isApiUrl(url: string): boolean {
  return url === '/api' || url.startsWith('/api/') || url.startsWith('/api?');
}
