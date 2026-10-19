import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withNavigationErrorHandler,
} from '@angular/router';
import { routes } from './app.routes';
import { unauthorizedInterceptor } from './core/http/unauthorized.interceptor';
import { provideStaleChunkReset, reloadOnStaleChunk } from './core/routing/stale-chunk';

/**
 * Zoneless (Angular 22 default). HttpClient keeps its built-in XSRF support: it reads the
 * `XSRF-TOKEN` cookie and sends `X-XSRF-TOKEN` on same-origin writes, so every API URL is a
 * relative `/api/...` path. A lazy chunk missing after a redeploy triggers one full reload.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([unauthorizedInterceptor])),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      withNavigationErrorHandler(reloadOnStaleChunk),
    ),
    provideStaleChunkReset(),
  ],
};
