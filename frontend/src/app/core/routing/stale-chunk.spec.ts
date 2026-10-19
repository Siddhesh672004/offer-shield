import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withNavigationErrorHandler } from '@angular/router';
import { FullPageLoad } from '../browser/full-page-load';
import {
  STALE_CHUNK_RELOAD_FLAG,
  isChunkLoadError,
  provideStaleChunkReset,
  reloadOnStaleChunk,
} from './stale-chunk';

@Component({ template: '<h1>Fine</h1>' })
class FineStub {}

const staleChunk = () =>
  Promise.reject(
    new TypeError(
      'Failed to fetch dynamically imported module: http://localhost/chunk-OLDHASH1.js',
    ),
  );

describe('stale chunk reload', () => {
  let router: Router;
  let load: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    sessionStorage.clear();
    load = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'fine', component: FineStub },
            { path: 'join', loadComponent: staleChunk },
            {
              path: 'broken',
              loadComponent: () => Promise.reject(new Error('Template parse failed')),
            },
          ],
          withNavigationErrorHandler(reloadOnStaleChunk),
        ),
        provideStaleChunkReset(),
        { provide: FullPageLoad, useValue: { load } },
      ],
    });
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  async function navigate(url: string): Promise<void> {
    await router.navigateByUrl(url).catch(() => undefined);
  }

  it('chunkLoadFailure_reloadsTargetOnceAndSetsFlag', async () => {
    await navigate('/join?from=menu');

    expect(load).toHaveBeenCalledExactlyOnceWith('/join?from=menu');
    expect(sessionStorage.getItem(STALE_CHUNK_RELOAD_FLAG)).not.toBeNull();
  });

  it('chunkLoadFailure_afterAlreadyReloading_doesNotReloadAgain', async () => {
    sessionStorage.setItem(STALE_CHUNK_RELOAD_FLAG, '1');

    await navigate('/join');

    expect(load).not.toHaveBeenCalled();
  });

  it('otherNavigationError_doesNotReload', async () => {
    await navigate('/broken');

    expect(load).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(STALE_CHUNK_RELOAD_FLAG)).toBeNull();
  });

  it('storageBlocked_doesNotReload', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Storage is blocked', 'SecurityError');
    });

    await navigate('/join');

    expect(load).not.toHaveBeenCalled();
  });

  it('successfulNavigation_clearsFlag', async () => {
    sessionStorage.setItem(STALE_CHUNK_RELOAD_FLAG, '1');

    await navigate('/fine');

    expect(sessionStorage.getItem(STALE_CHUNK_RELOAD_FLAG)).toBeNull();
  });
});

describe('isChunkLoadError', () => {
  it.each([
    ['Chrome', new TypeError('Failed to fetch dynamically imported module: /chunk-A.js')],
    ['Firefox', new TypeError('error loading dynamically imported module: /chunk-A.js')],
    ['Safari', new TypeError('Importing a module script failed.')],
    ['webpack', Object.assign(new Error('Loading chunk 7 failed.'), { name: 'ChunkLoadError' })],
  ])('isChunkLoadError_%s_true', (_browser, error) => {
    expect(isChunkLoadError(error)).toBe(true);
  });

  it.each([
    ['plain error', new Error('Template parse failed')],
    ['string', 'Failed to fetch dynamically imported module'],
    ['nothing', undefined],
  ])('isChunkLoadError_%s_false', (_label, error) => {
    expect(isChunkLoadError(error)).toBe(false);
  });
});
