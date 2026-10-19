import {
  DOCUMENT,
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideEnvironmentInitializer,
} from '@angular/core';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { filter } from 'rxjs';
import { FullPageLoad, sameOriginPath } from '../browser/full-page-load';

/** Set just before the one reload; cleared by the next successful navigation. */
export const STALE_CHUNK_RELOAD_FLAG = 'offershield.stale-chunk-reload';

/** What browsers say when a lazy chunk can't be loaded (Chrome/Edge, Firefox, Safari, webpack). */
const CHUNK_FAILURE =
  /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Loading chunk [\w-]+ failed/i;

export function isChunkLoadError(error: unknown): boolean {
  return (
    error instanceof Error && (error.name === 'ChunkLoadError' || CHUNK_FAILURE.test(error.message))
  );
}

/**
 * For `withNavigationErrorHandler`. After a redeploy, an open tab still asks for the old
 * hashed chunks, which no longer exist, so navigation fails. Load the target page once from the
 * server to pick up the new build. The sessionStorage flag allows one reload: if the chunk is
 * still missing afterwards, or storage is blocked, nothing reloads, so this can never loop.
 */
export function reloadOnStaleChunk(event: NavigationError): void {
  if (!isChunkLoadError(event.error)) {
    return;
  }
  const view = inject(DOCUMENT).defaultView;
  try {
    // Reading `sessionStorage` itself throws in some browsers when storage is blocked.
    const storage = view?.sessionStorage;
    if (!storage || storage.getItem(STALE_CHUNK_RELOAD_FLAG) !== null) {
      return;
    }
    storage.setItem(STALE_CHUNK_RELOAD_FLAG, '1');
  } catch {
    return;
  }
  inject(FullPageLoad).load(sameOriginPath(event.url) ?? '/');
}

/** Clears the reload flag after each successful navigation, so a later redeploy can reload again. */
export function provideStaleChunkReset(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideEnvironmentInitializer(() => {
      const view = inject(DOCUMENT).defaultView;
      inject(Router)
        .events.pipe(filter((event) => event instanceof NavigationEnd))
        .subscribe(() => {
          try {
            view?.sessionStorage.removeItem(STALE_CHUNK_RELOAD_FLAG);
          } catch {
            // Storage blocked: there is no flag to clear.
          }
        });
    }),
  ]);
}
